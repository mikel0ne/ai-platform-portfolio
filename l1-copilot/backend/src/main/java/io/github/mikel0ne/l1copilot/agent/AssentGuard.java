package io.github.mikel0ne.l1copilot.agent;

import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves a bare assent ("ok", "sí", "d'accord"...) against the real conversation state instead of the LLM:
 * with nothing awaited it cannot authorise an action, whatever the history claims.
 */
public final class AssentGuard {

    static final int MAX_LENGTH = 60;
    static final int MAX_WORDS = 6;

    private static final Set<Intent> ACTIONS = EnumSet.of(Intent.CANCEL, Intent.RETURN, Intent.SUPPORT);

    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\p{Mn}]");
    private static final Pattern REPEATED = Pattern.compile("(\\p{L})\\1+");
    private static final Pattern SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIGIT = Pattern.compile("\\p{N}");
    private static final Pattern ASSENT_SYMBOL =
            Pattern.compile("\\x{1F44D}[\\x{1F3FB}-\\x{1F3FF}]?|\\x{1F44C}|\\x{2705}|\\x{2714}|\\x{1F197}");

    private static final List<String> FILLER_PHRASES = normalizedList(
            "por favor", "si us plau", "s il vous plait", "s il te plait", "eskerrik asko", "thank you");

    private static final List<String> ASSENT_PHRASES = normalizedList(
            "de acuerdo", "d accord", "d acord", "go ahead", "do it", "vas y", "ados dago", "pode ser");

    private static final Set<String> ASSENT_WORDS = normalizedSet(
            "sí", "vale", "ok", "okay", "okey", "oki", "dale", "venga", "claro", "correcto", "perfecto", "exacto",
            "adelante", "confirmo", "confirmado", "hazlo", "procede", "yes", "yeah", "yep", "yup", "sure", "fine",
            "alright", "correct", "proceed", "confirm", "confirmed", "agreed", "oui", "ouais", "parfait", "confirme",
            "sim", "certo", "beleza", "combinado", "perfecte", "endavant", "entesos", "bai", "ados", "aurrera",
            "noski", "ondo");

    private static final Set<String> FILLER_WORDS = normalizedSet(
            "pues", "entonces", "ya", "bueno", "porfa", "xfa", "please", "pls", "plz", "gracias", "thanks", "merci",
            "obrigado", "obrigada", "mesedez", "eh", "ah", "oh", "mm", "vamos", "bien");

    private AssentGuard() {
    }

    public static RouterDecision apply(RouterDecision decision, Awaiting awaiting, Optional<Intent> offered,
                                       String message) {
        if (awaiting != Awaiting.NOTHING || !isBareAssent(message)) {
            return decision;
        }
        Intent llm = Intent.fromLlm(decision.intent());
        Intent resolved = offered.filter(o -> o != Intent.NONE && o != Intent.OTHER)
                .orElse(ACTIONS.contains(llm) ? Intent.OTHER : llm);
        return new RouterDecision(resolved.name(), null, "NONE", "NONE", decision.orderRelated());
    }

    public static boolean isBareAssent(String message) {
        if (message == null || message.isBlank() || message.strip().length() > MAX_LENGTH) {
            return false;
        }
        String text = normalize(ASSENT_SYMBOL.matcher(message).replaceAll(" ok "));
        if (DIGIT.matcher(text).find()) {
            return false;
        }
        String joined = " " + String.join(" ", SEPARATOR.split(text.strip())) + " ";
        for (String phrase : FILLER_PHRASES) {
            joined = joined.replace(" " + phrase + " ", " ");
        }
        for (String phrase : ASSENT_PHRASES) {
            joined = joined.replace(" " + phrase + " ", " ok ");
        }
        String[] words = Arrays.stream(joined.strip().split(" ")).filter(w -> !w.isEmpty()).toArray(String[]::new);
        if (words.length == 0 || words.length > MAX_WORDS) {
            return false;
        }
        return Arrays.stream(words).allMatch(w -> ASSENT_WORDS.contains(w) || FILLER_WORDS.contains(w))
                && Arrays.stream(words).anyMatch(ASSENT_WORDS::contains);
    }

    static String normalize(String raw) {
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFKD);
        folded = INVISIBLE.matcher(folded).replaceAll("").toLowerCase(Locale.ROOT);
        return REPEATED.matcher(folded).replaceAll("$1");
    }

    private static Set<String> normalizedSet(String... words) {
        return Stream.of(words).map(AssentGuard::normalize).collect(Collectors.toUnmodifiableSet());
    }

    private static List<String> normalizedList(String... phrases) {
        return Stream.of(phrases).map(AssentGuard::normalize).distinct().toList();
    }
}
