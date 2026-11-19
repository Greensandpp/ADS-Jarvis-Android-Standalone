package com.adsjarvis.standalone.core;

/**
 * GreenSoil PAPER-ONLY risk constraints. Defaults are the owner's stated rules;
 * lot sizes and fee estimates are editable because exchanges change them.
 */
public final class TradingRules {
    public int earliestEntryMinutes = 9 * 60 + 30;   // 09:30 IST
    public int lastEntryMinutes = 15 * 60;            // no fresh entries after 15:00 IST
    public double minConfidence = 70;
    public double maxRiskPerEntry = 1200;              // ₹ incl. fees + slippage
    public double dailyHardLoss = 3000;                // ₹
    public int maxOpenPositions = 1;                   // across NIFTY + SENSEX
    public int maxQuoteAgeSeconds = 60;
    public int niftyLot = 75;                          // verify with NSE before use
    public int sensexLot = 20;                         // verify with BSE before use
    public double brokeragePerOrder = 20;              // ₹ per executed order
    public double statutoryPct = 0.12;                 // % of premium turnover (STT, exchange, GST, stamp) – estimate
    public double slippagePointsPerUnit = 0.5;         // premium points per unit, entry + exit combined
    public double minRewardRisk = 1.5;                 // to TP2
    public double[] ladderR = {1, 1.5, 2, 2.5, 3, 4, 5, 6};
    public final boolean paperOnly = true;             // cannot be switched off in this app

    public int lot(String instrument) {
        return "SENSEX".equalsIgnoreCase(instrument) ? sensexLot : niftyLot;
    }
}
