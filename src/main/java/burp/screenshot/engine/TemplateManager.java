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

public class TemplateManager {

    private static final String CONFIG_FILE_NAME = ".burp_poc_screenshot_templates.json";
    /** Matches {@code HighlightRule}'s own default, so a repaired rule looks untouched. */
    private static final String DEFAULT_HIGHLIGHT = "#e5c07b";
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
        // 1. Default Template
        TemplateConfig def = new TemplateConfig("Default");
        templates.put("Default", def);

        // 2. Bug Bounty & Pentest Template
        TemplateConfig bb = new TemplateConfig("Bug Bounty PoC");
        bb.getRedactions().add(new RedactionRule(
                "session_id=[^;\\s]+",
                true,
                ScopeTarget.BOTH,
                RedactionMode.BLUR,
                0
        ));
        bb.getRedactions().add(new RedactionRule(
                "Bearer\\s+([a-zA-Z0-9_.-]+)",
                true,
                ScopeTarget.REQUEST,
                RedactionMode.BLUR,
                1
        ));
        bb.getHighlights().add(new HighlightRule(
                "(?i)(union\\s+select|<script.*?>|alert\\(.*?\\)|SUPERADMIN|sec_live_[a-zA-Z0-9]+)",
                true,
                ScopeTarget.BOTH,
                "#e5c07b"
        ));
        templates.put(bb.getName(), bb);

        // 3. Minimal Card
        TemplateConfig min = new TemplateConfig("Minimal");
        min.setContentWidth("Compact (800px)");
        min.setShowTimestamp(false);
        templates.put(min.getName(), min);
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
                System.err.println("[Screenshot PoC] Error reading templates: " + e.getMessage());
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
            System.err.println("[Screenshot PoC] Error saving templates: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ repair

    /**
     * Repairs a config that was deserialized from a file carrying explicit {@code null}s.
     *
     * <p>Gson omits null fields when it writes, so a null here means the JSON was written by an
     * older version or edited by hand. Every one of them is a latent crash: a null
     * {@code highlights} list throws on the first {@code for} over it, a null {@code layoutMode}
     * throws in the renderer's {@code ==} comparison, and a null {@code name} throws inside the
     * combo box's {@code toString}. The defaults applied are the ones the constructor would have
     * used, so a repaired template behaves like a fresh one.
     *
     * <p>Mutates and returns the same instance. The caller is normally the inspector's own live
     * config, and repairing it in place is the point.
     *
     * @return the repaired config, or null if it was null to begin with
     */
    public static TemplateConfig normalize(TemplateConfig c) {
        if (c == null) return null;

        if (isBlank(c.getName())) c.setName("Default");
        if (c.getHeadersToHide() == null) c.setHeadersToHide("");
        if (c.getHeaderScope() == null) c.setHeaderScope(ScopeTarget.BOTH);
        if (c.getLayoutMode() == null) c.setLayoutMode(LayoutMode.SIDE_BY_SIDE);
        if (isBlank(c.getContentWidth())) c.setContentWidth("Medium (1000px)");
        if (c.getRequestLineRanges() == null) c.setRequestLineRanges("");
        if (c.getResponseLineRanges() == null) c.setResponseLineRanges("");
        if (isBlank(c.getWindowStyle())) c.setWindowStyle("Caido");
        if (c.getWatermarkText() == null) c.setWatermarkText("");
        // Also catches NaN, which is what a JSON "not a number" deserializes to and which would
        // otherwise reach BufferedImage's width calculation.
        if (!(c.getScaleFactor() > 0)) c.setScaleFactor(2.0);
        // A template written before the divider existed deserializes to 0.0, and a divider
        // dragged to either end would leave a pane with no room for a glyph. Both read as a
        // rendering fault rather than as a setting, so both come back to the default.
        if (!(c.getSplitRatio() >= 0.05 && c.getSplitRatio() <= 0.95)) c.setSplitRatio(0.5);

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
                if (Tokens.hex(h.getColorHex()) == null) h.setColorHex(DEFAULT_HIGHLIGHT);
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
                if (r.getMode() == null) r.setMode(RedactionMode.MASK);
                if (r.getCaptureGroup() < 0) r.setCaptureGroup(0);
                redactions.add(r);
            }
        }
        c.setRedactions(redactions);

        return c;
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
