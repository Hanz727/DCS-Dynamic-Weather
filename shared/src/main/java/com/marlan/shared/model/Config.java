package com.marlan.shared.model;

import lombok.Data;

/**
 * GSON Deserialization Class
 */
@Data
public class Config {
    private String spreadsheetId;
    private String spreadsheetRange;
    private boolean outputToSheets;
    private boolean outputToDiscord;
    private String customSevenZipPath;
    private int timeOffset;
    private int currentTime;
    private int firstCyclicTimeInSecs;
    private int cyclicWindows;
    private int cyclicLength;
    private int preEventTime;
    // Battle-damage pass (CVIC backend): remove dead units, bake CVIC_DZONE
    // scenery-destruction zones + trigger, write the changelog. false = the
    // updater touches weather only. Initialized true so configs that predate
    // the key keep the feature on (gson only overwrites present keys).
    private boolean unitRemoval = true;
    // Post the battle-damage changelog entry + the updated .miz to the
    // changelog webhook (secrets\changelog_webhook.json) — mission editors see
    // what changed and get the file. Fires ONLY when units/zones actually
    // changed, never for weather-only updates (METOC covers those). Opt-in.
    private boolean outputMizToDiscord;
}
