package com.altnautica.gcs.radio.rx.bind

/** A byte stream to the drone's bind server: CRLF-terminated lines, fields separated by tabs. */
interface BindStream {
    suspend fun write(bytes: ByteArray)

    /** The next line without its terminator, or null when none arrived within [timeoutMs] or the stream ended. */
    suspend fun readLine(timeoutMs: Long): String?
}

/** How the exchange with the drone's bind server ended. */
sealed interface ExchangeResult {
    /** The drone took the key: `OK` to `BIND`. [droneVersion] is what it answered `VERSION` with. */
    data class Accepted(val droneVersion: String) : ExchangeResult

    /** The drone refused, with its reason. */
    data class Refused(val reason: String) : ExchangeResult

    /** No answer, or an answer that is not the protocol. */
    data class NoAnswer(val step: String) : ExchangeResult
}

/**
 * The ground's half of wfb-ng's bind exchange, as its bind client script runs
 * it against the drone's bind server: `VERSION` answered `OK<TAB>version`,
 * then `BIND<TAB><bundle>` answered `OK` or `ERR<TAB>reason`.
 */
class BindExchange(private val replyTimeoutMs: Long = REPLY_TIMEOUT_MS) {

    suspend fun run(stream: BindStream, bundleB64: String): ExchangeResult {
        stream.write(line("VERSION"))
        val version = stream.readLine(replyTimeoutMs) ?: return ExchangeResult.NoAnswer("VERSION")
        val (vStatus, vDetail) = split(version)
        if (vStatus != "OK") return refusedOrUnexpected(vStatus, vDetail, "VERSION")

        stream.write(line("BIND\t$bundleB64"))
        val bound = stream.readLine(replyTimeoutMs) ?: return ExchangeResult.NoAnswer("BIND")
        val (bStatus, bDetail) = split(bound)
        if (bStatus != "OK") return refusedOrUnexpected(bStatus, bDetail, "BIND")
        return ExchangeResult.Accepted(vDetail.orEmpty())
    }

    private fun refusedOrUnexpected(status: String, detail: String?, step: String): ExchangeResult =
        if (status == "ERR") ExchangeResult.Refused(detail?.ifBlank { null } ?: "no reason given") else ExchangeResult.NoAnswer(step)

    companion object {
        const val REPLY_TIMEOUT_MS = 15_000L

        fun line(text: String): ByteArray = "$text\r\n".toByteArray(Charsets.US_ASCII)

        /** Status and the rest of a reply line, split at the first tab. */
        fun split(reply: String): Pair<String, String?> {
            val tab = reply.indexOf('\t')
            return if (tab < 0) reply.trim() to null else reply.substring(0, tab).trim() to reply.substring(tab + 1)
        }
    }
}

/** Splits received bytes into lines at LF, dropping a CR before it. */
class LineSplitter {
    private val pending = StringBuilder()

    fun push(bytes: ByteArray): List<String> {
        pending.append(bytes.toString(Charsets.ISO_8859_1))
        val lines = mutableListOf<String>()
        while (true) {
            val lf = pending.indexOf("\n")
            if (lf < 0) break
            val end = if (lf > 0 && pending[lf - 1] == '\r') lf - 1 else lf
            lines += pending.substring(0, end)
            pending.delete(0, lf + 1)
        }
        return lines
    }
}

/**
 * Whether a drone really took part, as the agent's own bind gate decides it
 * for the ground: frames decoded on the bind receive plane during this
 * session, and the drone's `OK`. A clean exchange alone is never enough.
 */
object PeerProof {
    fun paired(framesDecoded: Long, result: ExchangeResult): Boolean = framesDecoded > 0 && result is ExchangeResult.Accepted
}
