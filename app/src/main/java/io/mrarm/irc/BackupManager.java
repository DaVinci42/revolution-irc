package io.mrarm.irc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.UnzipParameters;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.mrarm.irc.preference.ListWithCustomPreference;
import io.mrarm.irc.util.SettingsHelper;

public class BackupManager {

    private static final String BACKUP_PREFERENCES_PATH = "preferences.json";
    private static final String BACKUP_PREF_VALUES_PREFIX = "pref_values/";
    private static final String BACKUP_SERVER_PREFIX = "servers/server-";
    private static final String BACKUP_SERVER_SUFFIX = ".json";
    private static final String BACKUP_NOTIFICATION_RULES_PATH = "notification_rules.json";
    private static final String BACKUP_COMMAND_ALIASES_PATH = "command_aliases.json";

    public static void createBackup(Context context, File file, String password) throws IOException {
        try {
            Log.d("BackupManager", "createBackup: " + file.getAbsolutePath());

            ZipFile zipFile = new ZipFile(file);
            ZipParameters params = new ZipParameters();
            params.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
            params.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);
            if (password != null) {
                params.setEncryptFiles(true);
                params.setEncryptionMethod(Zip4jConstants.ENC_METHOD_AES);
                params.setAesKeyStrength(Zip4jConstants.AES_STRENGTH_256);
                params.setPassword(password);
            }
            params.setSourceExternalStream(true);

            params.setFileNameInZip(BACKUP_PREFERENCES_PATH);
            zipFile.addStream(exportPreferencesToJson(context), params);

            for (File f : SettingsHelper.getInstance(context).getCustomFiles()) {
                params.setFileNameInZip(BACKUP_PREF_VALUES_PREFIX + f.getName());
                zipFile.addFile(file, params);
            }

            StringWriter writer;

            for (ServerConfigData data : ServerConfigManager.getInstance(context).getServers()) {
                writer = new StringWriter();
                SettingsHelper.getGson().toJson(data, writer);
                params.setFileNameInZip(BACKUP_SERVER_PREFIX + data.uuid + BACKUP_SERVER_SUFFIX);
                zipFile.addStream(new ByteArrayInputStream(writer.toString().getBytes()), params);
            }

            writer = new StringWriter();
            NotificationManager.saveUserRuleSettings(context, writer);
            params.setFileNameInZip(BACKUP_NOTIFICATION_RULES_PATH);
            zipFile.addStream(new ByteArrayInputStream(writer.toString().getBytes()), params);

            writer = new StringWriter();
            CommandAliasManager.getInstance(context).saveUserSettings(writer);
            params.setFileNameInZip(BACKUP_COMMAND_ALIASES_PATH);
            zipFile.addStream(new ByteArrayInputStream(writer.toString().getBytes()), params);
        } catch (ZipException e) {
            throw new IOException(e);
        }
    }

    public static boolean verifyBackupFile(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            FileHeader preferences = zipFile.getFileHeader(BACKUP_PREFERENCES_PATH);
            FileHeader notificationRules = zipFile.getFileHeader(BACKUP_NOTIFICATION_RULES_PATH);
            FileHeader commandAliases = zipFile.getFileHeader(BACKUP_COMMAND_ALIASES_PATH);
            return preferences != null && notificationRules != null && commandAliases != null;
        } catch (ZipException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean isBackupPasswordProtected(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            FileHeader preferences = zipFile.getFileHeader(BACKUP_PREFERENCES_PATH);
            return preferences.isEncrypted();
        } catch (ZipException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void restoreBackup(Context context, File file, String password) throws IOException {
        try {
            ZipFile zipFile = new ZipFile(file);

            if (password != null)
                zipFile.setPassword(password);

            Reader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(
                    zipFile.getFileHeader(BACKUP_PREFERENCES_PATH))));
            importPreferencesFromJson(context, reader);
            reader.close();

            ServerConfigManager.getInstance(context).deleteAllServers();

            for (Object header : zipFile.getFileHeaders()) {
                if (!(header instanceof FileHeader))
                    continue;
                FileHeader fileHeader = (FileHeader) header;
                if (fileHeader.getFileName().startsWith(BACKUP_SERVER_PREFIX) &&
                        fileHeader.getFileName().endsWith(BACKUP_SERVER_SUFFIX)) {
                    reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(
                            fileHeader)));
                    ServerConfigData data = SettingsHelper.getGson().fromJson(reader,
                            ServerConfigData.class);
                    reader.close();
                    ServerConfigManager.getInstance(context).saveServer(data);
                }
                if (fileHeader.getFileName().startsWith(BACKUP_PREF_VALUES_PREFIX)) {
                    String name = fileHeader.getFileName();
                    int iof = name.lastIndexOf('/');
                    if (iof != -1)
                        name = name.substring(iof + 1);
                    zipFile.extractFile(fileHeader,
                            ListWithCustomPreference.getCustomFilesDir(context).getAbsolutePath(),
                            null, name);
                }
            }

            reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(
                    zipFile.getFileHeader(BACKUP_NOTIFICATION_RULES_PATH))));
            NotificationManager.loadUserRuleSettings(reader);
            reader.close();
            NotificationManager.saveUserRuleSettings(context);

            reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(
                    zipFile.getFileHeader(BACKUP_COMMAND_ALIASES_PATH))));
            CommandAliasManager aliasManager = CommandAliasManager.getInstance(context);
            aliasManager.loadUserSettings(reader);
            reader.close();
            aliasManager.saveUserSettings();
        } catch (ZipException e) {
            throw new IOException(e);
        }
    }

    private static InputStream exportPreferencesToJson(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String str = SettingsHelper.getGson().toJson(prefs.getAll());
        return new ByteArrayInputStream(str.getBytes());
    }

    @SuppressLint("ApplySharedPref")
    private static void importPreferencesFromJson(Context context, Reader reader) {
        SharedPreferences.Editor prefs = PreferenceManager.getDefaultSharedPreferences(context).edit();
        SettingsHelper.getInstance(context).clear();
        JsonObject obj = SettingsHelper.getGson().fromJson(reader, JsonObject.class);
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement el = entry.getValue();
            if (el.isJsonArray()) {
                Set<String> items = new HashSet<>();
                for (JsonElement child : el.getAsJsonArray())
                    items.add(child.getAsString());
                prefs.putStringSet(entry.getKey(), items);
            } else {
                JsonPrimitive primitive = el.getAsJsonPrimitive();
                if (primitive.isBoolean()) {
                    prefs.putBoolean(entry.getKey(), primitive.getAsBoolean());
                } else if (primitive.isNumber()) {
                    Number number = primitive.getAsNumber();
                    if (number instanceof Float || number instanceof Double)
                        prefs.putFloat(entry.getKey(), number.floatValue());
                    else if (number instanceof Long)
                        prefs.putLong(entry.getKey(), number.longValue());
                    else
                        prefs.putInt(entry.getKey(), number.intValue());
                } else if (primitive.isString()) {
                    prefs.putString(entry.getKey(), primitive.getAsString());
                }
            }
        }
        prefs.commit(); // This will be called asynchronously
    }

}
