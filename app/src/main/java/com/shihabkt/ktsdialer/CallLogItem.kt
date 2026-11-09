package com.shihabkt.ktsdialer

/**
 * Data representation for an individual call log entry.
 *
 * @property id Unique identifier for the call log item.
 * @property name Contact name if available, or null/empty if unsaved.
 * @property number Phone number associated with the call.
 * @property time Formatted date/time string of the call.
 * @property callType Descriptive call type ('Incoming', 'Outgoing', 'Missed', 'Rejected').
 * @property duration Formatted duration in minutes and seconds.
 * @property simInfo Label of the SIM used for the call (e.g. 'SIM 1', 'SIM 2').
 */
data class CallLogItem(
    val id: Long,
    val name: String?,
    val number: String,
    val time: String,
    val callType: String,
    val duration: String,
    val simInfo: String = ""
)

typealias CallLogModel = CallLogItem
