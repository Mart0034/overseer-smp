package dev.overseersmp.overseer;

import java.text.Normalizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** All-ages filter shared by prayers (input) and replies (output). Deliberately conservative; extend via config filter.extra-words. */
public final class ContentFilter {
    /** Whole-token matches (after leet/diacritic normalisation). */
    private static final Set<String> WORDS = Set.of(
            "fuck", "fucking", "fucker", "fucked", "fck", "shit", "shitty", "bitch", "bitches", "cunt", "dick", "dicks", "cock",
            "pussy", "cunts", "asshole", "bastard", "whore", "slut", "twat", "wanker", "piss", "bollocks", "porn", "porno", "sex", "sexy",
            "nude", "nudes", "naked", "cum", "anal", "rape", "raped", "rapist", "molest", "incest", "pedo", "pedophile", "hentai",
            "orgasm", "boobs", "tits", "penis", "vagina", "dildo", "nazi", "nazis", "hitler", "kys", "suicide", "genocide");
    /** Substring matches on the squashed text (catches "f u c k", "n1gg3r"); long enough to avoid false positives. */
    private static final List<String> STEMS = List.of("nigg", "fagg", "retard", "fuck", "whore", "pedoph", "bitch", "shit");
    /** Politics: the god never touches it, and prayers about it get a refusal. */
    private static final Set<String> POLITICS = Set.of(
            "trump", "biden", "putin", "election", "elections", "democrat", "republican", "maga", "politics", "political",
            "communist", "fascist", "kamala", "obama", "brexit", "zelensky", "netanyahu");
    private static final Pattern MONEY = Pattern.compile(
            "[$€£]|https?|www\\.|\\.(com|net|org|gg|io)\\b|discord\\.gg|\\b(store|shop|buy|buying|purchase|donat\\w*|price\\w*|tebex|paypal|usd|eur|dollars?|euros?|cash|money|premium)\\b",
            Pattern.CASE_INSENSITIVE);

    private final Set<String> extra = new HashSet<>();

    public ContentFilter(Collection<String> extraWords) {
        for (String w : extraWords) {
            String n = normalize(w, false).trim();
            if (!n.isEmpty()) extra.add(n);
        }
    }

    /** True if the text contains profanity, slurs, sexual or political content. */
    public boolean isBlocked(String text) {
        if (text == null) return false;
        return blocked(normalize(text, false)) || blocked(normalize(text, true));
    }

    private boolean blocked(String norm) {
        String[] tokens = norm.trim().split("\s+");
        for (String t : tokens) {
            if (WORDS.contains(t) || POLITICS.contains(t) || extra.contains(t) || hasStem(t)) return true;
        }
        // letters spaced out one by one ("f u c k", "f.u.c.k"): re-join runs of single-letter tokens
        StringBuilder run = new StringBuilder();
        for (int i = 0; i <= tokens.length; i++) {
            if (i < tokens.length && tokens[i].length() == 1) { run.append(tokens[i]); continue; }
            if (run.length() >= 3 && (WORDS.contains(run.toString()) || hasStem(run.toString()))) return true;
            run.setLength(0);
        }
        String padded = " " + norm.trim() + " ";
        for (String x : extra) if (padded.contains(" " + x + " ")) return true;
        return false;
    }

    private static boolean hasStem(String token) {
        for (String s : STEMS) if (token.contains(s)) return true;
        return false;
    }

    /** True if a reply talks about money, the store, or contains a link (the god never does). */
    public boolean mentionsMoneyOrLinks(String text) {
        return text != null && MONEY.matcher(text).find();
    }

    static String normalize(String s, boolean bangAsI) {
        String d = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        StringBuilder sb = new StringBuilder(d.length());
        for (char c : d.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append(switch (c) {
                case '0' -> 'o';
                case '1', '|' -> 'i';
                case '!' -> bangAsI ? 'i' : ' ';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> (c >= 'a' && c <= 'z') ? c : ' ';
            });
        }
        return sb.toString().replaceAll(" +", " ");
    }
}
