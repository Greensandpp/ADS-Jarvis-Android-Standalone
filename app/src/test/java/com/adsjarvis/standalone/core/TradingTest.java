package com.adsjarvis.standalone.core;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class TradingTest {
    TradingRules rules = new TradingRules();

    RiskEngine.Setup good() {
        RiskEngine.Setup s = new RiskEngine.Setup();
        s.instrument = "NIFTY"; s.entry = 120; s.stopLoss = 110; s.confidence = 78; s.entryMinutesIst = 10 * 60 + 15;
        s.candleClosed = true; s.htfConfirmed = true; s.quoteAgeSeconds = 5;
        return s;
    }

    @Test public void validSetupIsSizedWithinRiskBudget() {
        RiskEngine.Decision d = RiskEngine.evaluate(good(), rules);
        assertTrue(d.reasons.toString(), d.trade);
        assertEquals(1, d.lots);              // (10+0.5)*75 + fees ≈ 827 → 1 lot under ₹1,200
        assertEquals(75, d.quantity);
        assertTrue(d.estimatedRisk <= 1200);
        assertEquals(8, d.targets.size());
        assertEquals(130, d.targets.get(0), 1e-9);
        assertEquals(180, d.targets.get(7), 1e-9);
        assertEquals("TRADE (PAPER)", d.toJson().getString("verdict"));
        assertTrue(d.toJson().getBoolean("paper_only"));
    }

    @Test public void everyRuleBlocksIndependently() {
        RiskEngine.Setup s;
        s = good(); s.entryMinutesIst = 9 * 60 + 25; assertBlocked(s, "09:30");
        s = good(); s.candleClosed = false; assertBlocked(s, "5-minute");
        s = good(); s.confidence = 69; assertBlocked(s, "below 70");
        s = good(); s.quoteAgeSeconds = -1; assertBlocked(s, "freshness unknown");
        s = good(); s.quoteAgeSeconds = 300; assertBlocked(s, "stale");
        s = good(); s.openPositions = 1; assertBlocked(s, "already open");
        s = good(); s.realizedLossToday = 3000; assertBlocked(s, "Daily hard-loss");
        s = good(); s.stopLoss = 100; assertBlocked(s, "too wide");     // 20.5*75 > 1200
        s = good(); s.stopLoss = 125; assertBlocked(s, "wrong side");
        s = good(); s.instrument = "BANKNIFTY"; assertBlocked(s, "Only NIFTY");
    }

    @Test public void remainingDailyBudgetShrinksPosition() {
        RiskEngine.Setup s = good(); s.realizedLossToday = 2500;     // only ₹500 left today
        assertBlocked(s, "too wide");
    }

    @Test public void sensexUsesItsLotSize() {
        RiskEngine.Setup s = good(); s.instrument = "SENSEX"; s.entry = 300; s.stopLoss = 280;
        RiskEngine.Decision d = RiskEngine.evaluate(s, rules);
        assertTrue(d.reasons.toString(), d.trade);
        assertEquals(0, d.quantity % 20);
        assertTrue(d.estimatedRisk <= 1200);
    }

    private void assertBlocked(RiskEngine.Setup s, String reasonFragment) {
        RiskEngine.Decision d = RiskEngine.evaluate(s, rules);
        assertFalse(d.trade);
        assertTrue(d.reasons + " should mention " + reasonFragment, d.reasons.toString().contains(reasonFragment));
    }

    @Test public void journalFindsErrorsViolationsAndHypotheses() throws Exception {
        String csv = "Date,Entry Time,Exit Time,Symbol,Side,Entry,Exit,SL,Qty,PnL,Fees,Confidence,MFE\n"
            + "2026-11-17,09:20,09:40,NIFTY,LONG,100,110,90,75,750,40,80,130\n"     // before 09:30, P&L mismatch (710 expected)… within tolerance? no: |750-710|=40 <50 ok
            + "2026-11-17,10:00,10:30,NIFTY,LONG,120,100,100,75,-1540,40,65,125\n"  // risk 1540 > 1200, low confidence
            + "2026-11-17,10:15,10:45,SENSEX,LONG,300,320,290,20,900,40,75,340\n"   // overlaps previous; P&L wrong (360)
            + "2026-11-17,10:15,10:45,SENSEX,LONG,300,320,290,20,900,40,75,340\n"   // duplicate
            + "2026-11-18,11:00,10:50,NIFTY,LONG,150,140,145,75,,40,72,\n";          // exit before entry
        JSONObject r = JournalAnalyzer.analyze(csv, rules);
        String errs = r.getJSONArray("data_errors").toString();
        String vio = r.getJSONArray("rule_violations").toString();
        assertTrue(errs, errs.contains("duplicate"));
        assertTrue(errs, errs.contains("exit time is before entry"));
        assertTrue(errs, errs.contains("does not match"));
        assertTrue(vio, vio.contains("before 09:30"));
        assertTrue(vio, vio.contains("< 70%"));
        assertTrue(vio, vio.contains("planned risk"));
        assertTrue(vio, vio.contains("overlapped"));
        assertEquals(4, r.getInt("trades"));
        assertTrue(r.getJSONArray("improvement_hypotheses").toString().contains("too few"));
        assertTrue(r.getString("note").contains("do not predict"));
    }

    @Test public void journalWithoutRequiredColumnsReportsIt() throws Exception {
        JSONObject r = JournalAnalyzer.analyze("a,b\n1,2\n", rules);
        assertTrue(r.getJSONArray("data_errors").toString().contains("Missing column: entry"));
    }
}
