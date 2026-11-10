package org.synccontacts.app;

import android.database.Cursor;
import android.provider.ContactsContract;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/** Identifies one Contacts Provider account and its optional data set. */
public record AddressBook(String accountName, String accountType, String dataSet) {
    /** Reads account identity from a raw-contact cursor. */
    public static AddressBook fromCursor(Cursor cursor) {
        return new AddressBook(
                cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)),
                cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.DATA_SET)));
    }

    /** Displays enough identity to distinguish similarly named collections. */
    public String label() {
        String base = accountName == null && accountType == null
                ? "Device-local contacts"
                : String.valueOf(accountName) + " (" + String.valueOf(accountType) + ")";
        return dataSet == null ? base : base + " / " + dataSet;
    }

    /** Returns a stable serialization that preserves null and empty values. */
    public String encode() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("name", accountName == null ? JSONObject.NULL : accountName);
        json.put("type", accountType == null ? JSONObject.NULL : accountType);
        json.put("dataSet", dataSet == null ? JSONObject.NULL : dataSet);
        return json.toString();
    }

    /** Restores an address-book identity without treating a missing value as local. */
    public static AddressBook decode(String encoded) throws JSONException {
        JSONObject json = new JSONObject(Objects.requireNonNull(encoded));
        if (!json.has("name") || !json.has("type") || !json.has("dataSet")) {
            throw new JSONException("Incomplete address-book identity");
        }
        return new AddressBook(readNullable(json, "name"), readNullable(json, "type"),
                readNullable(json, "dataSet"));
    }

    /** Distinguishes JSON null from an empty account identifier. */
    private static String readNullable(JSONObject json, String key) throws JSONException {
        return json.isNull(key) ? null : json.getString(key);
    }

    @Override
    public String toString() {
        return label();
    }
}
