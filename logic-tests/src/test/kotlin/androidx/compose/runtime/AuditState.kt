package androidx.compose.runtime
import kotlin.reflect.KProperty
class AuditState<T>(var value: T)
fun <T> mutableStateOf(value: T) = AuditState(value)
fun mutableIntStateOf(value: Int) = AuditState(value)
fun <T> mutableStateListOf(vararg values: T) = values.toMutableList()
operator fun <T> AuditState<T>.getValue(receiver: Any?, property: KProperty<*>) = value
operator fun <T> AuditState<T>.setValue(receiver: Any?, property: KProperty<*>, newValue: T) { value = newValue }
