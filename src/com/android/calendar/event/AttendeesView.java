/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calendar.event;

import com.android.calendar.CalendarEventModel.Attendee;
import com.android.calendar.ContactsAsyncHelper;
import com.android.calendar.R;
import com.android.calendar.event.EditEventHelper.AttendeeItem;
import com.android.common.Rfc822Validator;

import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Calendar.Attendees;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.StatusUpdates;
import android.text.TextUtils;
import android.text.util.Rfc822Token;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.QuickContactBadge;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;

public class AttendeesView extends LinearLayout implements View.OnClickListener {
    private static final String TAG = "AttendeesView";
    private static final boolean DEBUG = false;

    private static final int PRESENCE_PROJECTION_CONTACT_ID_INDEX = 0;
    private static final int PRESENCE_PROJECTION_PRESENCE_INDEX = 1;
    private static final int PRESENCE_PROJECTION_EMAIL_INDEX = 2;
    private static final int PRESENCE_PROJECTION_PHOTO_ID_INDEX = 3;

    private static final String[] PRESENCE_PROJECTION = new String[] {
        Email.CONTACT_ID,           // 0
        Email.CONTACT_PRESENCE,     // 1
        Email.DATA,                 // 2
        Email.PHOTO_ID,             // 3
    };

    private static final Uri CONTACT_DATA_WITH_PRESENCE_URI = Data.CONTENT_URI;
    private static final String CONTACT_DATA_SELECTION = Email.DATA + " IN (?)";

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final PresenceQueryHandler mPresenceQueryHandler;
    private final Drawable mDefaultBadge;

    // TextView shown at the top of each type of attendees
    // e.g.
    // Yes  <-- divider
    // example_for_yes <exampleyes@example.com>
    // No <-- divider
    // example_for_no <exampleno@example.com>
    private final View mDividerForYes;
    private final View mDividerForNo;
    private final View mDividerForMaybe;
    private final View mDividerForNoResponse;

    private Rfc822Validator mValidator;

    // Number of attendees responding or not responding.
    private int mYes;
    private int mNo;
    private int mMaybe;
    private int mNoResponse;

    public AttendeesView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mPresenceQueryHandler = new PresenceQueryHandler(context.getContentResolver());

        final Resources resources = context.getResources();
        mDefaultBadge = resources.getDrawable(R.drawable.ic_contact_picture);

        final CharSequence[] entries = resources.getTextArray(R.array.response_labels1);
        mDividerForYes = constructDividerView(entries[1]);
        mDividerForNo = constructDividerView(entries[3]);
        mDividerForMaybe = constructDividerView(entries[2]);
        mDividerForNoResponse = constructDividerView(entries[0]);
    }

    public void setRfc822Validator(Rfc822Validator validator) {
        mValidator = validator;
    }

    private View constructDividerView(CharSequence label) {
        final TextView textView = new TextView(mContext);
        textView.setText(label);
        textView.setTextAppearance(mContext, R.style.TextAppearance_EventInfo_Label);
        textView.setClickable(false);
        return textView;
    }

    /**
     * Inflates a layout for a given attendee view and set up each element in it, and returns
     * the constructed View object. The object is also stored in {@link AttendeeItem#mView}.
     */
    private View constructAttendeeView(AttendeeItem item) {
        final Attendee attendee = item.mAttendee;
        item.mView = mInflater.inflate(R.layout.contact_item, null);
        return updateAttendeeView(item);
    }

    /**
     * Set up each element in {@link AttendeeItem#mView} using the latest information. View
     * object is reused.
     */
    private View updateAttendeeView(AttendeeItem item) {
        final Attendee attendee = item.mAttendee;
        final View view = item.mView;
        final TextView nameView = (TextView)view.findViewById(R.id.name);
        nameView.setText(TextUtils.isEmpty(attendee.mName) ? attendee.mEmail : attendee.mName);
        if (item.mRemoved) {
            nameView.setPaintFlags(Paint.STRIKE_THRU_TEXT_FLAG | nameView.getPaintFlags());
        } else {
            nameView.setPaintFlags((~Paint.STRIKE_THRU_TEXT_FLAG) & nameView.getPaintFlags());
        }

        final ImageButton button = (ImageButton)view.findViewById(R.id.contact_remove);
        button.setVisibility(View.VISIBLE);
        button.setTag(item);
        if (item.mRemoved) {
            button.setImageResource(R.drawable.ic_btn_round_plus);
        } else {
            button.setImageResource(R.drawable.ic_btn_round_minus);
        }
        button.setOnClickListener(this);

        final QuickContactBadge badge = (QuickContactBadge)view.findViewById(R.id.badge);
        badge.setImageDrawable(item.mBadge);
        badge.assignContactFromEmail(item.mAttendee.mEmail, true);
        badge.setMaxHeight(60);
        if (item.mPresence != -1) {
            final ImageView presence = (ImageView) view.findViewById(R.id.presence);
            presence.setImageResource(StatusUpdates.getPresenceIconResourceId(item.mPresence));
            presence.setVisibility(View.VISIBLE);

        }

        return view;
    }

    public boolean contains(Attendee attendee) {
        final int size = getChildCount();
        for (int i = 0; i < size; i++) {
            final View view = getChildAt(i);
            if (view instanceof TextView) {  // divider
                continue;
            }
            AttendeeItem attendeeItem = (AttendeeItem)view.getTag();
            if (TextUtils.equals(attendee.mEmail, attendeeItem.mAttendee.mEmail)) {
                return true;
            }
        }
        return false;
    }

    private void addOneAttendee(Attendee attendee) {
        if (contains(attendee)) {
            return;
        }
        final AttendeeItem item = new AttendeeItem(attendee, -1 /* presence */, mDefaultBadge);
        final int status = attendee.mStatus;
        final String name = attendee.mName == null ? "" : attendee.mName;
        final int index;
        switch (status) {
        case Attendees.ATTENDEE_STATUS_ACCEPTED: {
            final int startIndex = 0;
            if (mYes == 0) {
                addView(mDividerForYes, startIndex);
            }
            mYes++;
            index = startIndex + mYes;
            break;
        }
        case Attendees.ATTENDEE_STATUS_DECLINED: {
            final int startIndex = (mYes == 0 ? 0 : 1 + mYes);
            if (mNo == 0) {
                addView(mDividerForNo, startIndex);
            }
            mNo++;
            index = startIndex + mNo;
            break;
        }
        case Attendees.ATTENDEE_STATUS_TENTATIVE: {
            final int startIndex = (mYes == 0 ? 0 : 1 + mYes) + (mNo == 0 ? 0 : 1 + mNo);
            if (mMaybe == 0) {
                addView(mDividerForMaybe, startIndex);
            }
            mMaybe++;
            index = startIndex + mMaybe;
            break;
        }
        default: {
            final int startIndex = (mYes == 0 ? 0 : 1 + mYes) + (mNo == 0 ? 0 : 1 + mNo) +
                    (mMaybe == 0 ? 0 : 1 + mMaybe);
            // We delay adding the divider for "No response".
            index = startIndex + mNoResponse;
            mNoResponse++;
            break;
        }
        }

        final View view = constructAttendeeView(item);
        view.setTag(item);
        addView(view, index);

        // We want "No Response" divider only when
        // - someone already answered in some way,
        // - there is attendees not responding yet, and
        // - divider isn't in the list yet
        if (mYes + mNo + mMaybe > 0 && mNoResponse > 0 &&
                mDividerForNoResponse.getParent() == null) {
            final int dividerIndex = (mYes == 0 ? 0 : 1 + mYes) + (mNo == 0 ? 0 : 1 + mNo) +
                    (mMaybe == 0 ? 0 : 1 + mMaybe);
            addView(mDividerForNoResponse, dividerIndex);
        }

        mPresenceQueryHandler.startQuery(item.mUpdateCounts + 1, item,
                CONTACT_DATA_WITH_PRESENCE_URI, PRESENCE_PROJECTION, CONTACT_DATA_SELECTION,
                new String[] { attendee.mEmail }, null);
    }

    public void addAttendees(ArrayList<Attendee> attendees) {
        synchronized (this) {
            for (final Attendee attendee : attendees) {
                addOneAttendee(attendee);
            }
        }
    }

    public void addAttendees(HashMap<String, Attendee> attendees) {
        synchronized (this) {
            for (final Attendee attendee : attendees.values()) {
                addOneAttendee(attendee);
            }
        }
    }

    public void addAttendees(String attendees) {
        final LinkedHashSet<Rfc822Token> addresses =
                EditEventHelper.getAddressesFromList(attendees, mValidator);
        synchronized (this) {
            for (final Rfc822Token address : addresses) {
                final Attendee attendee = new Attendee(address.getName(), address.getAddress());
                if (TextUtils.isEmpty(attendee.mName)) {
                    attendee.mName = attendee.mEmail;
                }
                addOneAttendee(attendee);
            }
        }
    }

    /**
     * Returns true when the attendee at that index is marked as "removed" (the name of
     * the attendee is shown with a strike through line).
     */
    public boolean isMarkAsRemoved(int index) {
        final View view = getChildAt(index);
        if (view instanceof TextView) {  // divider
            return false;
        }
        return ((AttendeeItem)view.getTag()).mRemoved;
    }

    // TODO put this into a Loader for auto-requeries
    private class PresenceQueryHandler extends AsyncQueryHandler {
        public PresenceQueryHandler(ContentResolver cr) {
            super(cr);
        }

        @Override
        protected void onQueryComplete(int queryIndex, Object cookie, Cursor cursor) {
            if (cursor == null || cookie == null) {
                if (DEBUG) {
                    Log.d(TAG, "onQueryComplete: cursor=" + cursor + ", cookie=" + cookie);
                }
                return;
            }

            final AttendeeItem item = (AttendeeItem)cookie;
            try {
                cursor.moveToPosition(-1);
                boolean found = false;
                int contactId = 0;
                int photoId = 0;
                int presence = 0;
                while (cursor.moveToNext()) {
                    String email = cursor.getString(PRESENCE_PROJECTION_EMAIL_INDEX);
                    int temp = 0;
                    temp = cursor.getInt(PRESENCE_PROJECTION_PHOTO_ID_INDEX);
                    // A photo id must be > 0 and we only care about the contact
                    // ID if there's a photo
                    if (temp > 0) {
                        photoId = temp;
                        contactId = cursor.getInt(PRESENCE_PROJECTION_CONTACT_ID_INDEX);
                    }
                    // Take the most available status we can find.
                    presence = Math.max(
                            cursor.getInt(PRESENCE_PROJECTION_PRESENCE_INDEX), presence);

                    found = true;
                    if (DEBUG) {
                        Log.d(TAG,
                                "onQueryComplete Id: " + contactId + " PhotoId: " + photoId
                                        + " Email: " + email + " updateCount:" + item.mUpdateCounts
                                        + " Presence:" + item.mPresence);
                    }
                }
                if (found) {
                    item.mPresence = presence;

                    if (photoId > 0 && item.mUpdateCounts < queryIndex) {
                        item.mUpdateCounts = queryIndex;
                        final Uri personUri = ContentUris.withAppendedId(Contacts.CONTENT_URI,
                                contactId);
                        // Query for this contacts picture
                        ContactsAsyncHelper.retrieveContactPhotoAsync(
                                mContext, item, new Runnable() {
                                    public void run() {
                                        updateAttendeeView(item);
                                    }
                                }, personUri);
                    }
                }
            } finally {
                cursor.close();
            }
        }
    }

    public Attendee getItem(int index) {
        final View view = getChildAt(index);
        if (view instanceof TextView) {  // divider
            return null;
        }
        return ((AttendeeItem)view.getTag()).mAttendee;
    }

    @Override
    public void onClick(View view) {
        // Button corresponding to R.id.contact_remove.
        final AttendeeItem item = (AttendeeItem) view.getTag();
        item.mRemoved = !item.mRemoved;
        updateAttendeeView(item);
    }
}