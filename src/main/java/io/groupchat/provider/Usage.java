package io.groupchat.provider;

/**
 * Raw usage / quota information extracted from a provider call.
 *
 * <p>Most public APIs do not expose a simple "remaining balance" endpoint, so the
 * practical signal is the rate-limit window reported on each response. Any field
 * may be null when the provider does not report it.
 */
public class Usage {

    /** Remaining units in the current window (tokens or requests). */
    public Long remaining;
    /** Window limit (tokens or requests). */
    public Long limit;
    /** Unit label, e.g. "tokens" or "requests". */
    public String unit;
    /** Short human note about the source, e.g. "rate-limit window". */
    public String note;

    public Usage() {
    }

    public Usage(Long remaining, Long limit, String unit, String note) {
        this.remaining = remaining;
        this.limit = limit;
        this.unit = unit;
        this.note = note;
    }

    /** True when both remaining and a positive limit are known. */
    public boolean hasPercent() {
        return remaining != null && limit != null && limit > 0;
    }

    /** Remaining as a 0-100 percentage, or null if unknown. */
    public Integer percent() {
        if (!hasPercent()) {
            return null;
        }
        long pct = Math.round(100.0 * remaining / limit);
        return (int) Math.max(0, Math.min(100, pct));
    }
}
