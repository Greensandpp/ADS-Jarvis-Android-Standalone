package com.adsjarvis.standalone.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Reads a trade journal CSV (header names are flexible) and reports performance,
 * rule violations, data errors and improvement hypotheses. Hypotheses are
 * suggestions to test with walk-forward/out-of-sample/paper data — not promises.
 */
public final class JournalAnalyzer {
    public static final class Trade {
        public int row; public String date = "", instrument = "", side = "", exitReason = "";
        public int entryMin = -1, exitMin = -1;
        public double entry = Double.NaN, exit = Double.NaN, sl = Double.NaN, qty = Double.NaN, pnl = Double.NaN, fees = 0,
            confidence = Double.NaN, mfe = Double.NaN;
    }

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') { if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = !q; }
            else if (c == ',' && !q) { out.add(cur.toString().trim()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString().trim());
        return out;
    }

    private static final Map<String, String[]> ALIASES = new HashMap<>();
    static {
        ALIASES.put("date", new String[]{"date", "day", "trade_date"});
        ALIASES.put("entry_time", new String[]{"entry_time", "time", "entrytime", "in_time"});
        ALIASES.put("exit_time", new String[]{"exit_time", "exittime", "out_time"});
        ALIASES.put("instrument", new String[]{"instrument", "symbol", "index", "underlying"});
        ALIASES.put("side", new String[]{"side", "direction", "type"});
        ALIASES.put("entry", new String[]{"entry", "entry_price", "buy", "buy_price"});
        ALIASES.put("exit", new String[]{"exit", "exit_price", "sell", "sell_price"});
        ALIASES.put("sl", new String[]{"sl", "stop", "stop_loss", "stoploss"});
        ALIASES.put("qty", new String[]{"qty", "quantity", "size"});
        ALIASES.put("pnl", new String[]{"pnl", "p&l", "profit", "net_pnl"});
        ALIASES.put("fees", new String[]{"fees", "charges", "brokerage"});
        ALIASES.put("confidence", new String[]{"confidence", "conf", "score"});
        ALIASES.put("exit_reason", new String[]{"exit_reason", "reason", "result"});
        ALIASES.put("mfe", new String[]{"mfe", "max_favorable", "peak"});
    }

    static int minutes(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(t == null ? "" : t);
        return m.find() ? Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) : -1;
    }

    static double num(String s) {
        if (s == null) return Double.NaN;
        String c = s.replace("₹", "").replace(",", "").trim();
        if (c.isEmpty()) return Double.NaN;
        try { return Double.parseDouble(c); } catch (NumberFormatException e) { return Double.NaN; }
    }

    public static List<Trade> parse(String csv, List<String> errors) {
        List<Trade> out = new ArrayList<>();
        String[] lines = csv.replace("\r", "").split("\n");
        if (lines.length < 2) { errors.add("Journal has no data rows."); return out; }
        List<String> header = splitCsv(lines[0]);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).toLowerCase(Locale.ROOT).replace(' ', '_');
            for (Map.Entry<String, String[]> e : ALIASES.entrySet()) for (String a : e.getValue()) if (h.equals(a)) col.putIfAbsent(e.getKey(), i);
        }
        for (String need : new String[]{"entry", "exit"}) if (!col.containsKey(need)) errors.add("Missing column: " + need);
        for (int r = 1; r < lines.length; r++) {
            if (lines[r].trim().isEmpty()) continue;
            List<String> f = splitCsv(lines[r]);
            Trade t = new Trade();
            t.row = r + 1;
            java.util.function.Function<String, String> g = k -> col.containsKey(k) && col.get(k) < f.size() ? f.get(col.get(k)) : "";
            t.date = g.apply("date"); t.instrument = g.apply("instrument").toUpperCase(Locale.ROOT); t.side = g.apply("side");
            t.entryMin = minutes(g.apply("entry_time")); t.exitMin = minutes(g.apply("exit_time"));
            t.entry = num(g.apply("entry")); t.exit = num(g.apply("exit")); t.sl = num(g.apply("sl")); t.qty = num(g.apply("qty"));
            t.pnl = num(g.apply("pnl")); double fe = num(g.apply("fees")); t.fees = Double.isNaN(fe) ? 0 : fe;
            t.confidence = num(g.apply("confidence")); t.exitReason = g.apply("exit_reason"); t.mfe = num(g.apply("mfe"));
            out.add(t);
        }
        return out;
    }

    public static JSONObject analyze(String csv, TradingRules rules) {
        List<String> errors = new ArrayList<>(), violations = new ArrayList<>(), hypotheses = new ArrayList<>();
        List<Trade> trades = parse(csv, errors);
        int wins = 0, losses = 0;
        double net = 0, grossWin = 0, grossLoss = 0, peak = 0, equity = 0, maxDd = 0, giveback = 0, fees = 0;
        int earlyExits = 0, lowConf = 0;
        Map<String, Double> dayLoss = new HashMap<>();
        Set<String> seen = new HashSet<>();
        List<int[]> windows = new ArrayList<>();
        List<String> windowDates = new ArrayList<>();
        for (Trade t : trades) {
            String id = t.date + "|" + t.entryMin + "|" + t.instrument + "|" + t.entry + "|" + t.qty;
            if (!seen.add(id)) { errors.add("Row " + t.row + ": duplicate of an earlier trade (excluded from stats)."); continue; }
            if (Double.isNaN(t.entry) || Double.isNaN(t.exit)) { errors.add("Row " + t.row + ": entry or exit price missing."); continue; }
            if (t.entryMin >= 0 && t.exitMin >= 0 && t.exitMin < t.entryMin) errors.add("Row " + t.row + ": exit time is before entry time.");
            boolean shortSide = t.side.toUpperCase(Locale.ROOT).startsWith("S");
            double computed = Double.NaN;
            if (!Double.isNaN(t.qty)) computed = (shortSide ? t.entry - t.exit : t.exit - t.entry) * t.qty - t.fees;
            double pnl = !Double.isNaN(t.pnl) ? t.pnl : computed;
            if (Double.isNaN(pnl)) { errors.add("Row " + t.row + ": cannot compute P&L (no qty or pnl)."); continue; }
            if (!Double.isNaN(t.pnl) && !Double.isNaN(computed) && Math.abs(t.pnl - computed) > Math.max(50, Math.abs(computed) * .05))
                errors.add(String.format(Locale.ROOT, "Row %d: recorded P&L %.0f does not match prices × qty − fees (%.0f).", t.row, t.pnl, computed));
            fees += t.fees;
            net += pnl; equity += pnl; peak = Math.max(peak, equity); maxDd = Math.max(maxDd, peak - equity);
            if (pnl > 0) { wins++; grossWin += pnl; } else { losses++; grossLoss += -pnl; }
            if (t.entryMin >= 0 && t.entryMin < rules.earliestEntryMinutes) violations.add("Row " + t.row + ": entry before 09:30 IST.");
            if (!Double.isNaN(t.confidence)) { if (t.confidence < rules.minConfidence) { lowConf++; violations.add("Row " + t.row + ": taken at " + (int) t.confidence + "% confidence (< 70%)."); } }
            if (!Double.isNaN(t.sl) && !Double.isNaN(t.qty)) {
                double risk = Math.abs(t.entry - t.sl) * t.qty + t.fees;
                if (risk > rules.maxRiskPerEntry) violations.add(String.format(Locale.ROOT, "Row %d: planned risk ₹%.0f > ₹%.0f.", t.row, risk, rules.maxRiskPerEntry));
            }
            if (pnl < 0) {
                double dl = dayLoss.merge(t.date, -pnl, Double::sum);
                if (dl > rules.dailyHardLoss && dl + pnl <= rules.dailyHardLoss) violations.add("Row " + t.row + ": daily loss passed ₹" + (int) rules.dailyHardLoss + " on " + t.date + ".");
            } else dayLoss.merge(t.date, -pnl, Double::sum);
            if (!Double.isNaN(t.mfe) && !Double.isNaN(t.qty)) {
                double best = (shortSide ? t.entry - t.mfe : t.mfe - t.entry) * t.qty;
                if (best > 0 && pnl < best) giveback += best - Math.max(pnl, 0);
                if (best > 0 && pnl > 0 && pnl < .4 * best) earlyExits++;
            }
            if (t.entryMin >= 0 && t.exitMin >= 0) {
                for (int i = 0; i < windows.size(); i++)
                    if (windowDates.get(i).equals(t.date) && t.entryMin < windows.get(i)[1] && windows.get(i)[0] < t.exitMin)
                        violations.add("Row " + t.row + ": overlapped another open position (single-position rule).");
                windows.add(new int[]{t.entryMin, t.exitMin}); windowDates.add(t.date);
            }
        }
        int n = wins + losses;
        double winRate = n == 0 ? 0 : 100.0 * wins / n;
        double avgWin = wins == 0 ? 0 : grossWin / wins, avgLoss = losses == 0 ? 0 : grossLoss / losses;
        double pf = grossLoss == 0 ? (grossWin > 0 ? Double.POSITIVE_INFINITY : 0) : grossWin / grossLoss;
        if (n > 0 && avgLoss > avgWin && winRate < 60) hypotheses.add("Average loss exceeds average win: test tighter structural SLs or skipping trades with reward/risk < 1.5.");
        if (giveback > Math.max(1, grossWin) * .3) hypotheses.add("Large profit giveback: test moving SL to entry at TP1 and trailing after TP2.");
        if (earlyExits >= 2) hypotheses.add("Several exits captured <40% of the move: test holding a runner with a trailing stop.");
        if (lowConf > 0) hypotheses.add("Trades below 70% confidence were taken: enforce the NO TRADE rule; compare their results separately.");
        if (fees > Math.abs(net) * .3 && fees > 0) hypotheses.add("Fees/slippage are a large share of P&L: test fewer, higher-quality entries.");
        if (n < 30) hypotheses.add("Only " + n + " trades: too few for reliable conclusions; validate any change with walk-forward and paper trading.");
        try {
            return new JSONObject().put("trades", n).put("wins", wins).put("losses", losses)
                .put("win_rate_pct", Math.round(winRate * 10) / 10.0).put("net_pnl", Math.round(net))
                .put("avg_win", Math.round(avgWin)).put("avg_loss", Math.round(avgLoss))
                .put("profit_factor", Double.isInfinite(pf) ? "inf" : String.valueOf(Math.round(pf * 100) / 100.0))
                .put("max_drawdown", Math.round(maxDd)).put("fees", Math.round(fees)).put("profit_giveback", Math.round(giveback))
                .put("data_errors", new JSONArray(errors)).put("rule_violations", new JSONArray(violations))
                .put("improvement_hypotheses", new JSONArray(hypotheses))
                .put("note", "Statistics describe past trades only; they do not predict future results.");
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
