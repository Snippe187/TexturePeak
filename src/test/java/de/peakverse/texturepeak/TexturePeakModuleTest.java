package de.peakverse.texturepeak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.peakverse.core.api.ModuleInfo;
import de.peakverse.core.api.module.PeakModule;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/** Schuetzt die Grundregeln eines Moduls: gueltige ID, ServiceLoader-Eintrag, Standard-Config im Jar. Weitere Tests des Moduls hier ergaenzen. */
class TexturePeakModuleTest {
    @Test
    void idIsValidForPeakCore() {
        TexturePeakModule module = new TexturePeakModule();
        new ModuleInfo(module.id(), module.displayName(), "0.1.0", 1); // wirft bei ungueltiger ID
    }

    @Test
    void isFoundByTheServiceLoader() {
        boolean found = ServiceLoader.load(PeakModule.class).stream().anyMatch(p -> p.type() == TexturePeakModule.class);
        assertTrue(found, "META-INF/services/de.peakverse.core.api.module.PeakModule fehlt oder nennt die Klasse nicht");
    }

    @Test
    void defaultConfigIsAtTheModulePath() {
        assertNotNull(getClass().getResource("/modules/" + new TexturePeakModule().id() + "/config.yml"));
    }

    @Test
    void disableWithoutEnableIsSafe() {
        new TexturePeakModule().disable();
        assertEquals("texturepeak", new TexturePeakModule().id());
    }
}
