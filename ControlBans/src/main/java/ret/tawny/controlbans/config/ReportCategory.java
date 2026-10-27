package ret.tawny.controlbans.config;

import org.bukkit.Material;

import java.util.List;

/**
 * Immutable report category loaded from config.yml (report.categories).
 * Material is validated on load with a BARRIER fallback.
 */
public record ReportCategory(String id, Material icon, String name, List<String> lore) {

    public ReportCategory {
        if (id == null || id.isBlank()) id = "other";
        if (icon == null) icon = Material.BARRIER;
        if (name == null || name.isBlank()) name = id;
        lore = lore == null ? List.of() : List.copyOf(lore);
    }
}
