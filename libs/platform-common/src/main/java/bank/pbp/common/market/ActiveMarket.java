package bank.pbp.common.market;

import java.time.ZoneId;

/**
 * The deployment's market for code that Spring cannot inject into: Temporal workflow code, which Temporal
 * constructs itself. Set once at start-up from the market pack bean ({@link MarketGuard}); constant for the life
 * of the process, so workflow replay stays deterministic. Until it is set (unit tests without Spring) it answers
 * with the UK, today's only market.
 */
public final class ActiveMarket {

    private static final ZoneId UK = ZoneId.of("Europe/London");
    private static volatile ZoneId zone = UK;

    private ActiveMarket() {
    }

    /** The market's local time zone: cut-offs, due dates and run times. */
    public static ZoneId zone() {
        return zone;
    }

    static void set(MarketPack pack) {
        zone = pack.zone();
    }
}
