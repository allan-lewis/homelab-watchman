package com.allanlewis.homelabwatchman.resolve;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort "natural sort" for version-ish tags (semver, calendar
 * versions, with or without a leading "v"): splits into alternating
 * numeric/non-numeric runs and compares numeric runs numerically,
 * everything else lexicographically. Not a full semver implementation -
 * good enough to find "is there anything newer" across the tag shapes
 * actually seen in allans-home-lab (plain semver, "vX.Y", calendar+hash).
 */
public class NaturalVersionComparator implements Comparator<String> {

    private static final Pattern TOKEN = Pattern.compile("\\d+|\\D+");

    @Override
    public int compare(String a, String b) {
        Matcher ma = TOKEN.matcher(a);
        Matcher mb = TOKEN.matcher(b);

        while (ma.find() && mb.find()) {
            String ta = ma.group();
            String tb = mb.group();

            boolean numeric = Character.isDigit(ta.charAt(0)) && Character.isDigit(tb.charAt(0));
            int cmp = numeric
                    ? new BigInteger(ta).compareTo(new BigInteger(tb))
                    : ta.compareTo(tb);

            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.length(), b.length());
    }
}
