package org.synccontacts.app;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncAdapterType;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Discovers the local book, visible contacts, and installed contacts accounts. */
public final class ContactBooks {
    private ContactBooks() {
    }

    /** Reports provider failures instead of treating a failed query as an empty book. */
    public static List<AddressBook> discover(Context context) {
        ContentResolver resolver = context.getContentResolver();
        String[] columns = {
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.DATA_SET
        };
        Set<AddressBook> books = new LinkedHashSet<>();
        books.add(new AddressBook(null, null, null));
        try (Cursor cursor = resolver.query(ContactsContract.RawContacts.CONTENT_URI, columns,
                ContactsContract.RawContacts.DELETED + " = 0", null, null)) {
            if (cursor == null) {
                throw new IllegalStateException("Contacts Provider did not return an address-book list");
            }
            while (cursor.moveToNext()) {
                books.add(AddressBook.fromCursor(cursor));
            }
        }
        Set<String> contactAccountTypes = new LinkedHashSet<>();
        for (SyncAdapterType adapter : ContentResolver.getSyncAdapterTypes()) {
            if (ContactsContract.AUTHORITY.equals(adapter.authority)) {
                contactAccountTypes.add(adapter.accountType);
            }
        }
        AccountManager accounts = AccountManager.get(context);
        for (String accountType : contactAccountTypes) {
            for (Account account : accounts.getAccountsByType(accountType)) {
                books.add(new AddressBook(account.name, account.type, null));
            }
        }
        List<AddressBook> result = new ArrayList<>(books);
        result.sort((left, right) -> left.label().compareToIgnoreCase(right.label()));
        return result;
    }

    /** Verifies the selected local or sync-adapter account even if its book is empty. */
    public static boolean isAvailable(Context context, AddressBook book) {
        if (book.accountName() == null && book.accountType() == null && book.dataSet() == null) {
            return true;
        }
        if (book.accountName() == null || book.accountType() == null) {
            return false;
        }
        String selection = ContactsContract.RawContacts.ACCOUNT_NAME + " = ? AND "
                + ContactsContract.RawContacts.ACCOUNT_TYPE + " = ? AND "
                + ContactsContract.RawContacts.DATA_SET
                + (book.dataSet() == null ? " IS NULL" : " = ?");
        String[] arguments = book.dataSet() == null
                ? new String[]{book.accountName(), book.accountType()}
                : new String[]{book.accountName(), book.accountType(), book.dataSet()};
        try (Cursor cursor = context.getContentResolver().query(
                ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts._ID}, selection, arguments, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return true;
            }
        }
        Account account = new Account(book.accountName(), book.accountType());
        for (Account candidate : AccountManager.get(context).getAccountsByType(book.accountType())) {
            if (!candidate.equals(account)) {
                continue;
            }
            for (SyncAdapterType adapter : ContentResolver.getSyncAdapterTypes()) {
                if (ContactsContract.AUTHORITY.equals(adapter.authority)
                        && book.accountType().equals(adapter.accountType)) {
                    return true;
                }
            }
        }
        return false;
    }
}
