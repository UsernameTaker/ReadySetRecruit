package org.readysetrecruit.scrape;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal robots.txt support: reads the rules for "User-agent: *" (or our own agent token)
 * and answers whether a path may be fetched. Longest matching rule wins, Allow beats Disallow on ties.
 */
public final class RobotsTxt {
    private record Rule(String prefix, boolean allow) {}

    private final List<Rule> rules;

    private RobotsTxt(List<Rule> rules) { this.rules = rules; }

    public static RobotsTxt allowAll() { return new RobotsTxt(List.of()); }

    public static RobotsTxt parse(String body, String agentToken) {
        List<Rule> star = new ArrayList<>();
        List<Rule> ours = new ArrayList<>();
        List<String> currentAgents = new ArrayList<>();
        boolean lastWasAgent = false;
        for (String raw : body.split("\\R")) {
            String line = raw.replaceAll("#.*", "").trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            if (key.equals("user-agent")) {
                if (!lastWasAgent) currentAgents.clear();
                currentAgents.add(value.toLowerCase());
                lastWasAgent = true;
                continue;
            }
            lastWasAgent = false;
            if (!key.equals("allow") && !key.equals("disallow")) continue;
            if (key.equals("disallow") && value.isEmpty()) continue; // "Disallow:" = allow everything
            Rule rule = new Rule(value.replace("*", ""), key.equals("allow"));
            for (String agent : currentAgents) {
                if (agent.equals("*")) star.add(rule);
                else if (agentToken.toLowerCase().contains(agent)) ours.add(rule);
            }
        }
        return new RobotsTxt(ours.isEmpty() ? star : ours);
    }

    public boolean allows(String path) {
        String p = (path == null || path.isEmpty()) ? "/" : path;
        Rule best = null;
        for (Rule r : rules) {
            if (p.startsWith(r.prefix())) {
                if (best == null || r.prefix().length() > best.prefix().length()
                        || (r.prefix().length() == best.prefix().length() && r.allow())) best = r;
            }
        }
        return best == null || best.allow();
    }
}
