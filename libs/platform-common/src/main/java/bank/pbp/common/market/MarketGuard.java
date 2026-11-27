package bank.pbp.common.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses to start a service whose built-in market pack is not the market it was deployed for
 * ({@code PBP_MARKET}), as the module switches do for modules. A service without a pack is not checked.
 */
public final class MarketGuard {

    private static final Logger log = LoggerFactory.getLogger(MarketGuard.class);

    private MarketGuard() {
    }

    public static void check(String configured, MarketPack pack) {
        if (pack == null) {
            return;
        }
        String wanted = configured == null || configured.isBlank() ? "uk" : configured.trim().toLowerCase();
        if (!wanted.equals(pack.code())) {
            throw new IllegalStateException("This build carries the '" + pack.code() + "' market pack but PBP_MARKET is '"
                    + wanted + "'. Build with -Pmarket=" + wanted + " (or PBP_MARKET) and deploy that build.");
        }
        ActiveMarket.set(pack);
        log.info("Market pack '{}' active: {}, {}, {}", pack.code(), pack.currency(), pack.zone(), pack.locale());
    }
}
