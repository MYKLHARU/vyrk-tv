package androidx.media3.common
import com.vyrk.core.playback.ErrorClass
object C { const val TIME_UNSET = -9223372036854775807L; const val TRACK_TYPE_AUDIO = 1; const val TRACK_TYPE_TEXT = 3; const val ENCODING_PCM_16BIT = 2 }
object Player { const val STATE_BUFFERING = 2; const val STATE_ENDED = 4 }
class PlaybackException(val errorClass: ErrorClass) : Exception() { val errorCodeName = errorClass.name }
