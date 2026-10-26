package org.readysetrecruit.scrape;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Converts US state names to 2-letter codes ("California" -> "CA"); passes codes through. */
public final class States {
    private static final Map<String, String> BY_NAME = new HashMap<>();
    static {
        String[][] s = {{"Alabama","AL"},{"Alaska","AK"},{"Arizona","AZ"},{"Arkansas","AR"},{"California","CA"},
            {"Colorado","CO"},{"Connecticut","CT"},{"Delaware","DE"},{"District of Columbia","DC"},{"Florida","FL"},
            {"Georgia","GA"},{"Hawaii","HI"},{"Idaho","ID"},{"Illinois","IL"},{"Indiana","IN"},{"Iowa","IA"},
            {"Kansas","KS"},{"Kentucky","KY"},{"Louisiana","LA"},{"Maine","ME"},{"Maryland","MD"},
            {"Massachusetts","MA"},{"Michigan","MI"},{"Minnesota","MN"},{"Mississippi","MS"},{"Missouri","MO"},
            {"Montana","MT"},{"Nebraska","NE"},{"Nevada","NV"},{"New Hampshire","NH"},{"New Jersey","NJ"},
            {"New Mexico","NM"},{"New York","NY"},{"North Carolina","NC"},{"North Dakota","ND"},{"Ohio","OH"},
            {"Oklahoma","OK"},{"Oregon","OR"},{"Pennsylvania","PA"},{"Rhode Island","RI"},{"South Carolina","SC"},
            {"South Dakota","SD"},{"Tennessee","TN"},{"Texas","TX"},{"Utah","UT"},{"Vermont","VT"},
            {"Virginia","VA"},{"Washington","WA"},{"West Virginia","WV"},{"Wisconsin","WI"},{"Wyoming","WY"},
            {"Puerto Rico","PR"},{"Guam","GU"},{"U.S. Virgin Islands","VI"}};
        for (String[] p : s) {
            BY_NAME.put(p[0].toLowerCase(Locale.ROOT), p[1]);
            BY_NAME.put(p[1].toLowerCase(Locale.ROOT), p[1]);
        }
    }

    private States() {}

    public static String toCode(String nameOrCode) {
        if (nameOrCode == null) return null;
        return BY_NAME.get(nameOrCode.trim().toLowerCase(Locale.ROOT));
    }
}
