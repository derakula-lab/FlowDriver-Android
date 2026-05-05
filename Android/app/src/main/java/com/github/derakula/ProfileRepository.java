package com.github.derakula;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class ProfileRepository {

    private static final String PREF_FILE     = "xvpn_profiles";
    private static final String KEY_PROFILES  = "profiles";
    private static final String KEY_ACTIVE_ID = "active_profile_id";

    private final SharedPreferences prefs;

    public ProfileRepository(Context ctx) {
        prefs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    // ── Load all ──────────────────────────────────────────────
    public List<VpnProfile> loadAll() {
        List<VpnProfile> list = new ArrayList<>();
        try {
            String raw = prefs.getString(KEY_PROFILES, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++)
                list.add(VpnProfile.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return list;
    }

    // ── Save all ──────────────────────────────────────────────
    public void saveAll(List<VpnProfile> list) {
        try {
            JSONArray arr = new JSONArray();
            for (VpnProfile p : list) arr.put(p.toJson());
            prefs.edit().putString(KEY_PROFILES, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ── Upsert single ─────────────────────────────────────────
    public void save(VpnProfile profile) {
        List<VpnProfile> list = loadAll();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(profile.id)) {
                list.set(i, profile);
                saveAll(list);
                return;
            }
        }
        list.add(profile);
        saveAll(list);
    }

    // ── Delete ────────────────────────────────────────────────
    public void delete(String id) {
        List<VpnProfile> list = loadAll();
        list.removeIf(p -> p.id.equals(id));
        saveAll(list);
    }

    // ── Active profile ────────────────────────────────────────
    public String getActiveId() { return prefs.getString(KEY_ACTIVE_ID, null); }
    public void   setActiveId(String id) { prefs.edit().putString(KEY_ACTIVE_ID, id).apply(); }

    public VpnProfile getActive() {
        String id = getActiveId();
        if (id == null) return null;
        for (VpnProfile p : loadAll())
            if (p.id.equals(id)) return p;
        return null;
    }
}