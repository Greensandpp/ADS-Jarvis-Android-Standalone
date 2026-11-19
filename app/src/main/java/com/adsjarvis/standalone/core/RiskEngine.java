package com.adsjarvis.standalone.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Deterministic gate applied to every trade idea (from the AI, a chart read or
 * the owner). The AI may propose; only this engine decides TRADE vs NO TRADE,
 * and every reason is listed. PAPER ONLY — it never places orders.
 */
public final class RiskEngine {
    public static final class Setup {
        public String instrument = "NIFTY";      // NIFTY | SENSEX
        public String side = "LONG";              // LONG premium (bought CE/PE) | SHORT
        public double entry, stopLoss;            // option premium levels
        public double confidence;                 // 0–100 from the analysis
        public int entryMinutesIst = -1;          // minutes since midnight IST of the signal
        public boolean candleClosed;              // 5-minute candle confirmed closed
        public boolean htfConfirmed;              // 15-minute confirmation
        public int quoteAgeSeconds = -1;          // -1 = unknown
        public double realizedLossToday;          // ₹, positive number = loss
        public int openPositions;
        public String evidence = "";
    }

    public static final class Decision {
        public boolean trade;
        public final List<String> reasons = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public int lots, quantity;
        public double riskPerUnit, estimatedRisk, estimatedFees, rewardRiskTp2;
        public final List<Double> targets = new ArrayList<>();
        public final List<String> trailPlan = new ArrayList<>();

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject().put("verdict", trade ? "TRADE (PAPER)" : "NO TRADE")
                    .put("reasons", new JSONArray(reasons)).put("warnings", new JSONArray(warnings))
                    .put("lots", lots).put("quantity", quantity)
                    .put("risk_per_unit", round(riskPerUnit)).put("estimated_risk_rupees", round(estimatedRisk))
                    .put("estimated_fees_rupees", round(estimatedFees)).put("reward_risk_tp2", round(rewardRiskTp2))
                    .put("paper_only", true);
                JSONArray t = new JSONArray();
                for (int i = 0; i < targets.size(); i++) t.put(new JSONObject().put("tp", "TP" + (i + 1)).put("price", round(targets.get(i))));
                return o.put("targets", t).put("trailing_plan", new JSONArray(trailPlan));
            } catch (JSONException e) { throw new IllegalStateException(e); }
        }
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    public static Decision evaluate(Setup s, TradingRules r) {
        Decision d = new Decision();
        String inst = s.instrument == null ? "" : s.instrument.toUpperCase(Locale.ROOT);
        if (!inst.equals("NIFTY") && !inst.equals("SENSEX")) d.reasons.add("Only NIFTY and SENSEX are supported.");
        if (s.entry <= 0 || s.stopLoss <= 0) d.reasons.add("Entry and stop-loss prices are required.");
        boolean longPrem = !"SHORT".equalsIgnoreCase(s.side);
        if (s.entry > 0 && s.stopLoss > 0 && (longPrem ? s.stopLoss >= s.entry : s.stopLoss <= s.entry))
            d.reasons.add("Stop-loss is on the wrong side of entry.");
        if (s.entryMinutesIst < 0) d.reasons.add("Signal time unknown — cannot confirm the 09:30 IST rule.");
        else {
            if (s.entryMinutesIst < r.earliestEntryMinutes) d.reasons.add("Before 09:30 IST — no entries yet.");
            if (s.entryMinutesIst > r.lastEntryMinutes) d.reasons.add("Too late in the session for a fresh entry.");
            if (s.entryMinutesIst % 5 != 0) d.warnings.add("Signal time is not on a 5-minute boundary.");
        }
        if (!s.candleClosed) d.reasons.add("Waiting for a confirmed 5-minute candle close.");
        if (!s.htfConfirmed) d.warnings.add("15-minute higher timeframe does not confirm.");
        if (s.confidence < r.minConfidence)
            d.reasons.add(String.format(Locale.ROOT, "Confidence %.0f%% is below %.0f%%.", s.confidence, r.minConfidence));
        if (s.quoteAgeSeconds < 0) d.reasons.add("Quote freshness unknown — treat as stale.");
        else if (s.quoteAgeSeconds > r.maxQuoteAgeSeconds) d.reasons.add("Quote is stale (" + s.quoteAgeSeconds + "s old).");
        if (s.openPositions >= r.maxOpenPositions) d.reasons.add("A position is already open (limit " + r.maxOpenPositions + " across NIFTY/SENSEX).");
        if (s.realizedLossToday >= r.dailyHardLoss) d.reasons.add("Daily hard-loss limit of ₹" + (int) r.dailyHardLoss + " reached.");

        if (s.entry > 0 && s.stopLoss > 0) {
            int lot = r.lot(inst);
            double gap = Math.abs(s.entry - s.stopLoss);
            d.riskPerUnit = gap + r.slippagePointsPerUnit;
            // Fees for one lot (entry+exit at SL) to size conservatively.
            double feesPerLot = 2 * r.brokeragePerOrder + (s.entry + s.stopLoss) * lot * r.statutoryPct / 100.0;
            double perLotRisk = d.riskPerUnit * lot + feesPerLot;
            double budget = Math.min(r.maxRiskPerEntry, Math.max(0, r.dailyHardLoss - s.realizedLossToday));
            d.lots = perLotRisk <= 0 ? 0 : (int) Math.floor(budget / perLotRisk);
            if (d.lots < 1) d.reasons.add(String.format(Locale.ROOT,
                "Stop-loss too wide: one lot risks ₹%.0f including fees/slippage, above the ₹%.0f budget.", perLotRisk, budget));
            d.lots = Math.max(0, d.lots);
            d.quantity = d.lots * lot;
            d.estimatedFees = d.lots == 0 ? 0 : 2 * r.brokeragePerOrder + (s.entry + s.stopLoss) * d.quantity * r.statutoryPct / 100.0;
            d.estimatedRisk = d.riskPerUnit * d.quantity + d.estimatedFees;
            double R = gap;
            for (double m : r.ladderR) d.targets.add(longPrem ? s.entry + m * R : s.entry - m * R);
            if (d.targets.size() >= 2) {
                double rewardTp2 = Math.abs(d.targets.get(1) - s.entry) - r.slippagePointsPerUnit;
                d.rewardRiskTp2 = d.riskPerUnit == 0 ? 0 : rewardTp2 / d.riskPerUnit;
                if (d.rewardRiskTp2 < r.minRewardRisk) d.warnings.add("Reward/risk to TP2 below " + r.minRewardRisk + ".");
            }
            d.trailPlan.add("At TP1: move stop to entry (risk-free).");
            d.trailPlan.add("At TP2: trail stop to TP1.");
            d.trailPlan.add("From TP3: trail stop one target behind price; exit remainder on a 5-minute close beyond the trail.");
        }
        d.trade = d.reasons.isEmpty();
        return d;
    }
}
