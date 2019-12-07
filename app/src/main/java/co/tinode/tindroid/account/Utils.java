package co.tinode.tindroid.account;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Data;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import co.tinode.tindroid.Cache;
import co.tinode.tindroid.TindroidApp;
import co.tinode.tindroid.UiUtils;
import co.tinode.tindroid.db.BaseDb;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tinodesdk.ComTopic;
import co.tinode.tinodesdk.Tinode;
import co.tinode.tinodesdk.Topic;

import static androidx.core.content.ContextCompat.checkSelfPermission;

/**
 * Constants and misc utils
 */
public class Utils {
    private static final String TAG = "Utils";

    // Account management constants
    public static final String TOKEN_TYPE = "co.tinode.token";
    public static final String TOKEN_EXPIRATION_TIME = "co.tinode.token_expires";

    public static final String ACCOUNT_TYPE = "co.tinode.account";
    public static final String SYNC_AUTHORITY = "com.android.contacts";
    public static final String TINODE_IM_PROTOCOL = "Tinode";

    // Constants for accessing shared preferences
    public static final String PREFS_HOST_NAME = "pref_hostName";
    public static final String PREFS_USE_TLS = "pref_useTLS";

    // Prefixes for various contacts
    public static final String TAG_LABEL_PHONE = "tel:";
    public static final String TAG_LABEL_EMAIL = "email:";
    public static final String TAG_LABEL_TINODE = "tinode:";

    public static final int FETCH_EMAIL = 0x1;
    public static final int FETCH_PHONE = 0x2;
    public static final int FETCH_IM = 0x4;

    /**
     * MIME-type used when storing a profile {@link ContactsContract.Data} entry.
     */
    public static final String MIME_TINODE_PROFILE =
            "vnd.android.cursor.item/vnd.co.tinode.im";
    public static final String DATA_PID = Data.DATA1;
    public static final String DATA_SUMMARY = Data.DATA2;
    public static final String DATA_DETAIL = Data.DATA3;

    public static Account createAccount(String uid) {
        return new Account(uid, ACCOUNT_TYPE);
    }

    /**
     * Read address book contacts from the Contacts content provider.
     * The results are ordered by 'data1' field.
     *
     * @param resolver content resolver to use.
     * @param flags bit flags indicating types f contacts to fetch.
     *
     * @return contacts
     */
    public static SparseArray<ContactHolder> fetchContacts(ContentResolver resolver, int flags) {
        SparseArray<ContactHolder> map = new SparseArray<>();

        final String[] projection = {
                Data.CONTACT_ID,
                Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Email.DATA,
                ContactsContract.CommonDataKinds.Email.TYPE,
                ContactsContract.CommonDataKinds.Im.PROTOCOL,
                ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL,
        };

        // Need to make the list order consistent so the hash does not change too often.
        final String orderBy = ContactsContract.CommonDataKinds.Email.DATA;

        LinkedList<String> args = new LinkedList<>();
        if ((flags & FETCH_EMAIL) != 0) {
            args.add(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);
        }
        if ((flags & FETCH_PHONE) != 0) {
            args.add(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
        }
        if ((flags & FETCH_IM) != 0) {
            args.add(ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE);
        }
        if (args.size() == 0) {
            throw new IllegalArgumentException();
        }

        StringBuilder sel = new StringBuilder(Data.MIMETYPE);
        sel.append(" IN (");
        for (int i=0; i<args.size(); i++) {
            sel.append("?,");
        }
        // Strip final comma.
        sel.setLength(sel.length() - 1);
        sel.append(")");

        final String selection = sel.toString();

        final String[] selectionArgs = args.toArray(new String[]{});

        // Get contacts from the database.
        Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI, projection,
                selection, selectionArgs, orderBy);
        if (cursor == null) {
            Log.d(TAG, "Failed to fetch contacts");
            return map;
        }

        final int contactIdIdx = cursor.getColumnIndex(Data.CONTACT_ID);
        final int mimeTypeIdx = cursor.getColumnIndex(Data.MIMETYPE);
        final int dataIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA);
        final int typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.TYPE);
        final int imProtocolIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im.PROTOCOL);
        final int imProtocolNameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL);

        final PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
        final String country = Locale.getDefault().getCountry();

        while (cursor.moveToNext()) {
            int type = cursor.getInt(typeIdx);
            int contact_id = cursor.getInt(contactIdIdx);
            String data = cursor.getString(dataIdx);
            String mimeType = cursor.getString(mimeTypeIdx);

            // Log.d(TAG, "Got id=" + contact_id + ", mime='" + mimeType +"', val='" + data + "'");

            ContactHolder holder = map.get(contact_id);
            if (holder == null) {
                holder = new ContactHolder();
                map.put(contact_id, holder);
            }

            switch (mimeType) {
                case ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE:
                    // This is an email
                    //Log.d(TAG, "Adding email '" + data + "' to contact=" + contact_id);
                    holder.putEmail(data);
                    break;
                case ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE:
                    int protocol = cursor.getInt(imProtocolIdx);
                    String protocolName = cursor.getString(imProtocolNameIdx);
                    // Log.d(TAG, "Possibly adding IM '" + data + "' to contact=" + contact_id);
                    if (protocol == ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM &&
                            protocolName.equals(TINODE_IM_PROTOCOL)) {
                        holder.putIm(data);
                        // Log.d(TAG, "Added IM '" + data + "' to contact=" + contact_id);
                    }
                    break;
                case ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE:
                    // This is a phone number. Use mobile phones only.
                    if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                        try {
                            // Normalize phone number format
                            Phonenumber.PhoneNumber number = phoneUtil.parse(data, country);
                            if (phoneUtil.isValidNumber(number)) {
                                holder.putPhone(phoneUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164));
                            }
                        } catch (NumberParseException e) {
                            Log.i(TAG, "Failed to parse phone number '" + data + "' in country '" + country + "'");
                        }
                    }
                    break;
            }
        }
        cursor.close();

        return map;
    }

    // Generate a hash from a string.
    static String hash(String s) {
        if (s == null || s.equals("")) {
            return "";
        }

        try {
            // Create MD5 Hash
            MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            digest.update(s.getBytes());
            byte[] messageDigest = digest.digest();

            // Create a String from the byte array.
            StringBuilder hexString = new StringBuilder();
            for (byte x : messageDigest) {
                hexString.append(Integer.toString(0xFF & x, 32));
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        return String.valueOf(s.hashCode());
    }

    public static class ContactHolder {
        List<String> emails;
        List<String> phones;
        List<String> ims;

        ContactHolder() {
            emails = null;
            phones = null;
            ims = null;
        }

        // Inverse of toString: deserialize contacts from
        ContactHolder(final String[] matches) {
            // Initialize all content to null.
            this();
            // Log.i(TAG, "Processing matches: " + Arrays.toString(matches));
            // Parse contacts.
            for (String match : matches) {
                if (match.indexOf(TAG_LABEL_EMAIL) == 0) {
                    putEmail(match.substring(TAG_LABEL_EMAIL.length()));
                } else if (match.indexOf(TAG_LABEL_PHONE) == 0) {
                    putPhone(match.substring(TAG_LABEL_PHONE.length()));
                } else if (match.indexOf(TAG_LABEL_TINODE) == 0) {
                    putIm(match.substring(TAG_LABEL_TINODE.length()));
                }
            }
        }

        private static void Stringify(List<String> vals, String label, StringBuilder str) {
            if (vals != null && vals.size() > 0) {
                if (str.length() > 0) {
                    str.append(",");
                }

                for (String entry : vals) {
                    str.append(label);
                    str.append(entry);
                    str.append(",");
                }
                // Strip trailing comma.
                str.setLength(str.length() - 1);
            }
        }

        void putEmail(String email) {
            if (emails == null) {
                emails = new LinkedList<>();
            }
            emails.add(email);
        }

        void putPhone(String phone) {
            if (phones == null) {
                phones = new LinkedList<>();
            }
            phones.add(phone);
        }

        void putIm(String im) {
            if (ims == null) {
                ims = new LinkedList<>();
            }
            ims.add(im);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder str = new StringBuilder();
            Stringify(emails, TAG_LABEL_EMAIL, str);
            Stringify(phones, TAG_LABEL_PHONE, str);
            Stringify(ims, TAG_LABEL_TINODE, str);
            return str.toString();
        }
    }

    static boolean isPermissionGranted(Context context, String permission) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Fetch messages (and maybe topic description and subscriptions) in background.
     *
     * This method SHOULD NOT be called on UI thread.
     *
     * @param context context to use for resources.
     * @param topicName name of the topic to sync.
     * @param seq sequence ID of the new message to fetch.
     */
    public static void backgroundDataFetch(Context context, String topicName, int seq) {
        Log.d(TAG, "Background fetch for " + topicName);

        String uid = BaseDb.getInstance().getUid();
        if (TextUtils.isEmpty(uid)) {
            Log.w(TAG, "Data fetch failed: not logged in");
            return;
        }

        final AccountManager am = AccountManager.get(context);
        final Account account = UiUtils.getSavedAccount(context, am, uid);
        if (account == null) {
            Log.w(TAG, "Data fetch failed: account not found");
            return;
        }

        final SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        String hostName = sharedPref.getString(Utils.PREFS_HOST_NAME, TindroidApp.getDefaultHostName(context));
        boolean tls = sharedPref.getBoolean(Utils.PREFS_USE_TLS, TindroidApp.getDefaultTLS());
        final Tinode tinode = Cache.getTinode();
        // noinspection unchecked
        ComTopic<VxCard> topic = (ComTopic<VxCard>) tinode.getTopic(topicName);
        Topic.MetaGetBuilder builder;
        if (topic == null) {
            // New topic. Create it.
            // noinspection unchecked
            topic = (ComTopic<VxCard>) tinode.newTopic(topicName, null);
            builder = topic.getMetaGetBuilder().withDesc().withSub();
        } else {
            // Existing topic.
            builder = topic.getMetaGetBuilder();
        }
        if (topic.isAttached()) {
            Log.d(TAG, "Topic is already attached");
            // No need to fetch: topic is already subscribed and got data notification through normal channel.
            return;
        }

        if (topic.getSeq() < seq) {
            // Won't fetch if anything throws.
            try {
                // Will return immediately if it's already connected.
                tinode.connect(hostName, tls).getResult();

                String token = AccountManager.get(context).blockingGetAuthToken(account, Utils.TOKEN_TYPE, false);

                tinode.loginToken(token).getResult();

                // Fully asynchronous. We don't need to do anything with the result.
                // The new data will be automatically saved.
                topic.subscribe(null, builder.withLaterData(24).withDel().build(), true);
                topic.leave();
            } catch (Exception ex) {
                Log.w(TAG, "Failed to sync messages on push. Topic=" + topicName);
                // TODO: hand sync over to Worker.
            }
        } else {
            Log.d(TAG, "All messages are already received: oldSeq=" + topic.getSeq() + "; newSeq="+seq);
        }
    }
}
