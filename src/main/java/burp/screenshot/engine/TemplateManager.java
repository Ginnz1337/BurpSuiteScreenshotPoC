package burp.screenshot.engine;

import burp.screenshot.design.Tokens;
import burp.screenshot.model.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Persists the view settings between runs.
 *
 * <p>The file name changed when the extension stopped producing images. The old file holds
 * card geometry that no longer exists, and reading it would only seed dead keys; a new name
 * means the current defaults apply from the first launch rather than after a reset.
 */
public class TemplateManager {

    private static final String CONFIG_FILE_NAME = ".burp_poc_text_view.json";
    private final Path configFilePath;
    private final Gson gson;
    private final Map<String, TemplateConfig> templates;

    public TemplateManager() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        String userHome = System.getProperty("user.home");
        this.configFilePath = Paths.get(userHome, CONFIG_FILE_NAME);
        this.templates = new LinkedHashMap<>();
        load();
    }

    private void initDefaults() {
        // Normalized even though it was just built, so the file it is about to be written to
        // carries the version and the next load has nothing to reconcile.
        TemplateConfig def = normalize(new TemplateConfig("Default"));
        templates.put("Default", def);
    }

    public synchronized void load() {
        templates.clear();
        if (Files.exists(configFilePath)) {
            try (Reader reader = Files.newBufferedReader(configFilePath, StandardCharsets.UTF_8)) {
                Type type = new TypeToken<Map<String, TemplateConfig>>() {}.getType();
                Map<String, TemplateConfig> loaded = gson.fromJson(reader, type);
                if (loaded != null && !loaded.isEmpty()) {
                    for (Map.Entry<String, TemplateConfig> entry : loaded.entrySet()) {
                        TemplateConfig config = normalize(entry.getValue());
                        // A null value in the file is a template that does not exist. Dropping it
                        // here is what keeps getTemplateNames honest.
                        if (config != null) templates.put(entry.getKey(), config);
                    }
                }
            } catch (Exception e) {
                System.err.println("[PoC view] Error reading settings: " + e.getMessage());
            }
        }

        if (templates.isEmpty()) {
            initDefaults();
            save();
        }
    }

    public synchronized void save() {
        try {
            if (!Files.exists(configFilePath.getParent())) {
                Files.createDirectories(configFilePath.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(configFilePath, StandardCharsets.UTF_8)) {
                gson.toJson(templates, writer);
            }
        } catch (Exception e) {
            System.err.println("[PoC view] Error saving settings: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ repair

    /**
     * Repairs a config that was deserialized from a file carrying explicit {@code null}s.
     *
     * <p>Gson omits null fields when it writes, so a null here means the JSON was written by an
     * older version or edited by hand. Every one of them is a latent crash: a null
     * {@code highlights} list throws on the first {@code for} over it, and a null {@code name}
     * throws inside a combo box's {@code toString}. The defaults applied are the ones the
     * constructor would have used, so a repaired config behaves like a fresh one.
     *
     * <p>A field missing from the file altogether keeps the constructor's value, which is how a
     * settings file written before the default {@code Date} highlight existed still gets it.
     *
     * <p>Mutates and returns the same instance. The caller is normally the live config, and
     * repairing it in place is the point.
     *
     * @return the repaired config, or null if it was null to begin with
     */
    public static TemplateConfig normalize(TemplateConfig c) {
        if (c == null) return null;

        if (isBlank(c.getName())) c.setName("Default");
        if (c.getHeadersToHide() == null) c.setHeadersToHide("");
        if (c.getHeaderScope() == null) c.setHeaderScope(ScopeTarget.BOTH);
        if (c.getRequestLineRanges() == null) c.setRequestLineRanges("");
        if (c.getResponseLineRanges() == null) c.setResponseLineRanges("");

        Map<String, String> colors = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : c.getSyntaxColors().entrySet()) {
            if (e.getKey() != null && e.getValue() != null) colors.put(e.getKey(), e.getValue());
        }
        c.setSyntaxColors(colors);

        List<HighlightRule> highlights = new ArrayList<>();
        if (c.getHighlights() != null) {
            for (HighlightRule h : c.getHighlights()) {
                if (h == null) continue;
                if (h.getPattern() == null) h.setPattern("");
                if (h.getTarget() == null) h.setTarget(ScopeTarget.BOTH);
                if (Tokens.hex(h.getColorHex()) == null) h.setColorHex(TemplateConfig.DEFAULT_HIGHLIGHT);
                h.setPattern(upgradeDatePattern(h));
                highlights.add(h);
            }
        }
        c.setHighlights(highlights);

        List<RedactionRule> redactions = new ArrayList<>();
        if (c.getRedactions() != null) {
            for (RedactionRule r : c.getRedactions()) {
                if (r == null) continue;
                if (r.getPattern() == null) r.setPattern("");
                if (r.getTarget() == null) r.setTarget(ScopeTarget.BOTH);
                if (r.getCaptureGroup() < 0) r.setCaptureGroup(0);
                redactions.add(r);
            }
        }
        nameShippedRules(highlights, redactions);
        // Bring a saved config up to the shipped rule set, once. A file written before a default
        // existed carries the old version number, so the rules it never had are added here; a
        // file already at this version is left exactly as the user left it, deleted rules
        // included. Without the version a deleted Cookie rule would come back on every reload.
        if (c.getDefaultsVersion() < TemplateConfig.DEFAULTS_VERSION) {
            for (RedactionRule candidate : TemplateConfig.defaultRedactions()) {
                boolean alreadyThere = false;
                for (RedactionRule existing : redactions) {
                    if (existing.getPattern().equals(candidate.getPattern())) {
                        alreadyThere = true;
                        break;
                    }
                }
                if (!alreadyThere) redactions.add(candidate);
            }
            c.setDefaultsVersion(TemplateConfig.DEFAULTS_VERSION);
        }

        c.setRedactions(redactions);

        return c;
    }

    /**
     * Gives the shipped rules their names on a file that was written before they had any.
     *
     * <p>Only a blank name is filled, and only on a rule whose pattern is character for character
     * a shipped one. A file that predates names would otherwise open with seven unnamed rows
     * beside every rule the user has named, and the name the user typed is never overwritten.
     */
    private static void nameShippedRules(List<HighlightRule> highlights,
                                         List<RedactionRule> redactions) {
        for (HighlightRule h : highlights) {
            if (!h.getName().isEmpty()) continue;
            if (TemplateConfig.DEFAULT_DATE_PATTERN.equals(h.getPattern())) {
                h.setName(TemplateConfig.DEFAULT_DATE_NAME);
            }
        }
        for (RedactionRule r : redactions) {
            if (!r.getName().isEmpty()) continue;
            for (RedactionRule shipped : TemplateConfig.defaultRedactions()) {
                if (shipped.getPattern().equals(r.getPattern())) {
                    r.setName(shipped.getName());
                    break;
                }
            }
        }
    }

    /**
     * Widens the shipped {@code Date} rule to cover the value, not just the name.
     *
     * <p>The rule used to match {@code ^Date:}, so a highlighted response showed a yellow
     * {@code Date:} and a value left plain. A settings file written then still carries the old
     * pattern, and changing the default alone would never reach it: the file's rule is the one
     * that gets compiled. Rewriting it here is what makes an existing install show the fix.
     *
     * <p>Both conditions have to hold. A rule the user wrote themselves for the request side is
     * not this rule, and is left alone.
     */
    private static String upgradeDatePattern(HighlightRule rule) {
        if (TemplateConfig.LEGACY_DATE_PATTERN.equals(rule.getPattern())
                && rule.getTarget() == ScopeTarget.RESPONSE) {
            return TemplateConfig.DEFAULT_DATE_PATTERN;
        }
        return rule.getPattern();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public synchronized List<String> getTemplateNames() {
        return new ArrayList<>(templates.keySet());
    }

    public synchronized TemplateConfig getTemplate(String name) {
        TemplateConfig cfg = templates.get(name);
        if (cfg == null) {
            cfg = templates.get("Default");
        }
        return cfg != null ? cfg.copy() : new TemplateConfig("Default");
    }

    public synchronized void saveTemplate(TemplateConfig config) {
        if (config == null) return;
        // Normalize before copy: copy() reads the lists directly, so a null one would throw here
        // rather than at the next load.
        TemplateConfig clean = normalize(config);
        templates.put(clean.getName(), clean.copy());
        save();
    }

    public synchronized void deleteTemplate(String name) {
        if (!"Default".equalsIgnoreCase(name)) {
            templates.remove(name);
            save();
        }
    }
}
