package expo.modules.video

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.PlayerView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.sharedobjects.SharedObject
import expo.modules.video.enums.PlayerStatus
import expo.modules.video.enums.PlayerStatus.*
import expo.modules.video.records.VolumeEvent
import kotlinx.coroutines.launch

// https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide#improvements_in_media3
@UnstableApi
class VideoPlayer(context: Context, appContext: AppContext, private val mediaItem: MediaItem) : AutoCloseable, SharedObject(appContext) {
  private val currentActivity: Activity
    get() {
      return appContext?.activityProvider?.currentActivity
        ?: throw Exceptions.MissingActivity()
    }

  // This improves the performance of playing DRM-protected content
  private var renderersFactory = DefaultRenderersFactory(context)
    .forceEnableMediaCodecAsynchronousQueueing()

  private var loadControl = DefaultLoadControl.Builder()
    .setPrioritizeTimeOverSizeThresholds(false)
    .build()

  val player = ExoPlayer
    .Builder(context, renderersFactory)
    .setLooper(context.mainLooper)
    .setLoadControl(loadControl)
    .build()

  // We duplicate some properties of the player, because we don't want to always use the mainQueue to access them.
  var playing = false
    set(value) {
      if (field != value) {
        sendEventOnJSThread("playingChange", value, field, error?.message)
      }
      field = value
    }

  var status: PlayerStatus = IDLE
    set(value) {
      if (field != value) {
        sendEventOnJSThread("statusChange", value, field, error?.message)
      }
      field = value
    }

  var currentMediaItem: MediaItem? = null
    set(newMediaItem) {
      if (field != newMediaItem) {
        val oldVideoSource = VideoManager.getVideoSourceFromMediaItem(field)
        val newVideoSource = VideoManager.getVideoSourceFromMediaItem(newMediaItem)

        sendEventOnJSThread("sourceChange", newVideoSource, oldVideoSource)
      }
      field = newMediaItem
    }
  var error: CodedException? = null

  // Volume of the player if there was no mute applied.
  var userVolume = 1f
  var requiresLinearPlayback = false
  var staysActiveInBackground = false
  var preservesPitch = false
    set(preservesPitch) {
      playbackParameters = applyPitchCorrection(playbackParameters)
      field = preservesPitch
    }

  private var serviceConnection: ServiceConnection
  internal var playbackServiceBinder: PlaybackServiceBinder? = null
  lateinit var timeline: Timeline

  var volume = 1f
    set(volume) {
      if (player.volume == volume) return
      player.volume = if (muted) 0f else volume
      sendEventOnJSThread("volumeChange", VolumeEvent(volume, muted), VolumeEvent(field, muted))
      field = volume
    }

  var muted = false
    set(muted) {
      if (field == muted) return
      sendEventOnJSThread("volumeChange", VolumeEvent(volume, muted), VolumeEvent(volume, field))
      volume = if (muted) 0f else userVolume
      field = muted
    }

  var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    set(newPlaybackParameters) {
      if (playbackParameters.speed != newPlaybackParameters.speed) {
        sendEventOnJSThread("playbackRateChange", newPlaybackParameters.speed, playbackParameters.speed)
      }
      val pitchCorrectedPlaybackParameters = applyPitchCorrection(newPlaybackParameters)
      field = pitchCorrectedPlaybackParameters

      if (player.playbackParameters != pitchCorrectedPlaybackParameters) {
        player.playbackParameters = pitchCorrectedPlaybackParameters
      }
    }

  private val playerListener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      this@VideoPlayer.playing = isPlaying
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
      this@VideoPlayer.timeline = timeline
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      this@VideoPlayer.currentMediaItem = mediaItem
      if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
        sendEventOnJSThread("playToEnd")
      }
      super.onMediaItemTransition(mediaItem, reason)
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
      if (playbackState == Player.STATE_IDLE && error != null) {
        return
      }
      status = playerStateToPlayerStatus(playbackState)
      super.onPlaybackStateChanged(playbackState)
    }

    override fun onVolumeChanged(volume: Float) {
      this@VideoPlayer.volume = volume
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
      this@VideoPlayer.playbackParameters = playbackParameters
      super.onPlaybackParametersChanged(playbackParameters)
    }

    override fun onPlayerErrorChanged(error: PlaybackException?) {
      error?.let {
        val message = "${error.localizedMessage} ${error.cause?.message ?: ""}"
        this@VideoPlayer.error = PlaybackException(message, error)
        status = ERROR
      } ?: run {
        this@VideoPlayer.error = null
        status = playerStateToPlayerStatus(player.playbackState)
      }

      super.onPlayerErrorChanged(error)
    }
  }

  init {
    val intent = Intent(context, ExpoVideoPlaybackService::class.java)

    serviceConnection = object : ServiceConnection {
      override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
        playbackServiceBinder = binder as? PlaybackServiceBinder
        playbackServiceBinder?.service?.registerPlayer(player) ?: run {
          Log.w(
            "ExpoVideo",
            "Expo Video could not bind to the playback service. " +
              "This will cause issues with playback notifications and sustaining background playback."
          )
        }
      }

      override fun onServiceDisconnected(componentName: ComponentName) {
        playbackServiceBinder = null
      }

      override fun onNullBinding(componentName: ComponentName) {
        Log.w(
          "ExpoVideo",
          "Expo Video could not bind to the playback service. " +
            "This will cause issues with playback notifications and sustaining background playback."
        )
      }
    }
    intent.action = MediaSessionService.SERVICE_INTERFACE
    currentActivity.apply {
      startService(intent)
      bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }
    player.addListener(playerListener)
    VideoManager.registerVideoPlayer(this)
  }

  override fun close() {
    currentActivity.unbindService(serviceConnection)
    playbackServiceBinder?.service?.unregisterPlayer(player)
    VideoManager.unregisterVideoPlayer(this@VideoPlayer)

    appContext?.mainQueue?.launch {
      player.removeListener(playerListener)
      player.release()
    }
  }

  override fun deallocate() {
    close()
    super.deallocate()
  }

  fun changePlayerView(playerView: PlayerView) {
    player.clearVideoSurface()
    player.setVideoSurfaceView(playerView.videoSurfaceView as SurfaceView?)
    playerView.player = player
  }

  fun prepare() {
    player.setMediaItem(mediaItem)
    player.prepare()
  }

  private fun applyPitchCorrection(playbackParameters: PlaybackParameters): PlaybackParameters {
    val speed = playbackParameters.speed
    val pitch = if (preservesPitch) 1f else speed
    return PlaybackParameters(speed, pitch)
  }

  private fun playerStateToPlayerStatus(@Player.State state: Int): PlayerStatus {
    return when (state) {
      Player.STATE_IDLE -> IDLE
      Player.STATE_BUFFERING -> LOADING
      Player.STATE_READY -> READY_TO_PLAY
      Player.STATE_ENDED -> {
        // When an error occurs, the player state changes to ENDED.
        if (error != null) {
          ERROR
        } else {
          sendEventOnJSThread("playToEnd")
          IDLE
        }
      }

      else -> IDLE
    }
  }

  private fun sendEventOnJSThread(eventName: String, vararg args: Any?) {
    appContext?.executeOnJavaScriptThread {
      sendEvent(eventName, *args)
    }
  }
}
