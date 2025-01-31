/*
 * Copyright 2017 Adam Feinstein
 *
 * This file is part of MTG Familiar.
 *
 * MTG Familiar is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MTG Familiar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MTG Familiar.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.gelakinetic.mtgfam.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.SectionIndexer;
import android.widget.TextView;
import android.widget.TextView.BufferType;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;

import com.gelakinetic.mtgfam.FamiliarActivity;
import com.gelakinetic.mtgfam.R;
import com.gelakinetic.mtgfam.fragments.dialogs.RulesDialogFragment;
import com.gelakinetic.mtgfam.helpers.ImageGetterHelper;
import com.gelakinetic.mtgfam.helpers.PreferenceAdapter;
import com.gelakinetic.mtgfam.helpers.SnackbarWrapper;
import com.gelakinetic.mtgfam.helpers.database.CardDbAdapter;
import com.gelakinetic.mtgfam.helpers.database.DatabaseManager;
import com.gelakinetic.mtgfam.helpers.database.FamiliarDbException;
import com.gelakinetic.mtgfam.helpers.database.FamiliarDbHandle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RulesFragment extends FamiliarFragment {

    /* Keys for information in the bundle */
    public static final String CATEGORY_KEY = "category";
    public static final String SUBCATEGORY_KEY = "subcategory";
    private static final String POSITION_KEY = "position";
    private static final String ENTRY_KEY = "entry";
    public static final String KEYWORD_KEY = "keyword";
    private static final String GLOSSARY_KEY = "glossary";
    private static final String BANNED_KEY = "banned";
    private static final String FORMAT_KEY = "format";

    /* Keys for BannedItems */
    private static final String NONE = "NONE";
    private static final String SETS = "Sets"; // TODO untranslated string

    /* Current rules information */
    private ArrayList<DisplayItem> mRules;
    public int mCategory;
    public int mSubcategory;

    /* Regular expression patterns */
    private Pattern mUnderscorePattern;
    private Pattern mExamplePattern;
    private Pattern mGlyphPattern;
    private Pattern mKeywordPattern;
    private Pattern mHyperlinkPattern;
    private Pattern mLinkPattern;

    /**
     * Necessary empty constructor
     */
    public RulesFragment() {
    }

    /**
     * @param inflater           The LayoutInflater object that can be used to inflate any views in the fragment,
     * @param container          If non-null, this is the parent view that the fragment's UI should be attached to. The
     *                           fragment should not add the view itself, but this can be used to generate the
     *                           LayoutParams of the view.
     * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous saved state as given
     *                           here.
     * @return The view to be shown
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        String keyword;
        final String format;

        /* Inflate the view */
        View myFragmentView = inflater.inflate(R.layout.result_list_frag, container, false);
        assert myFragmentView != null;

        /* Get arguments to display a rules section, or use defaults */
        Bundle extras = getArguments();
        int position;
        boolean isGlossary;
        final boolean isBanned;
        String entryToScrollTo = null;
        if (extras == null) {
            mCategory = -1;
            mSubcategory = -1;
            position = 0;
            keyword = null;
            format = null;
            isGlossary = false;
            isBanned = false;
        } else {
            mCategory = extras.getInt(CATEGORY_KEY, -1);
            mSubcategory = extras.getInt(SUBCATEGORY_KEY, -1);
            position = extras.getInt(POSITION_KEY, 0);
            keyword = extras.getString(KEYWORD_KEY);
            format = extras.getString(FORMAT_KEY);
            isGlossary = extras.getBoolean(GLOSSARY_KEY, false);
            isBanned = extras.getBoolean(BANNED_KEY, false);
            entryToScrollTo = extras.getString(ENTRY_KEY, null);
        }

        ListView list = myFragmentView.findViewById(R.id.result_list);

        /* Set which side the fast scroll is shown on */
        if (getString(R.string.pref_right).equals(PreferenceAdapter.getFastScrollSidePref(getContext()))) {
            list.setVerticalScrollbarPosition(View.SCROLLBAR_POSITION_RIGHT);
        } else {
            list.setVerticalScrollbarPosition(View.SCROLLBAR_POSITION_LEFT);
        }

        mRules = new ArrayList<>();
        final boolean isClickable;
        final boolean isSearchResult;
        int positionToScrollTo = -1;

        /* Sub-optimal, but KitKat is silly */
        list.setOnScrollListener(new ListView.OnScrollListener() {

            @Override
            public void onScrollStateChanged(AbsListView absListView, int scrollState) {
                switch (scrollState) {
                    case AbsListView.OnScrollListener.SCROLL_STATE_IDLE:
                        absListView.setFastScrollAlwaysVisible(false);
                        break;
                    case AbsListView.OnScrollListener.SCROLL_STATE_FLING:
                        absListView.setFastScrollAlwaysVisible(true);
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onScroll(AbsListView absListView, int i, int i2, int i3) {

            }
        });

        /* Populate the cursor with information from the database */
        Cursor cursor = null;
        Cursor setsCursor = null;
        FamiliarDbHandle handle = new FamiliarDbHandle();
        try {
            /* Open a database connection */
            SQLiteDatabase database = DatabaseManager.openDatabase(getActivity(), false, handle);

            if (isGlossary) {
                cursor = CardDbAdapter.getGlossaryTerms(database);
                isClickable = false;
                isSearchResult = false;
            } else if (isBanned && format != null) {
                cursor = CardDbAdapter.getBannedCards(database, format);
                setsCursor = CardDbAdapter.getLegalSets(database, format);
                isClickable = false;
                isSearchResult = false;
            } else if (isBanned) {
                cursor = CardDbAdapter.fetchAllFormats(database);
                isClickable = true;
                isSearchResult = false;
            } else if (keyword == null) {
                cursor = CardDbAdapter.getRules(mCategory, mSubcategory, database);
                isClickable = mSubcategory == -1;
                isSearchResult = false;
            } else {
                cursor = CardDbAdapter.getRulesByKeyword(keyword, mCategory, mSubcategory, database);
                isClickable = false;
                isSearchResult = true;
            }

            /* Add DisplayItems to mRules */
            if (setsCursor != null) {
                if (setsCursor.getCount() > 0) {
                    setsCursor.moveToFirst();
                    mRules.add(new BannedItem(
                            format,
                            SETS,
                            CardDbAdapter.getStringFromCursor(setsCursor, CardDbAdapter.KEY_LEGAL_SETS), false));
                }
                if (null == cursor || cursor.getCount() == 0) { // Adapter will not be set when cursor has count 0
                    int listItemResource = R.layout.rules_list_detail_item;
                    RulesListAdapter adapter = new RulesListAdapter(getActivity(), listItemResource, mRules, isSearchResult);
                    list.setAdapter(adapter);
                }
            }
            if (cursor != null) {
                if (cursor.getCount() > 0) {
                    cursor.moveToFirst();
                    while (!cursor.isAfterLast()) {
                        if (isGlossary) {
                            mRules.add(new GlossaryItem(
                                    CardDbAdapter.getStringFromCursor(cursor, CardDbAdapter.KEY_TERM),
                                    CardDbAdapter.getStringFromCursor(cursor, CardDbAdapter.KEY_DEFINITION), false));
                        } else if (isBanned && format != null) {
                            mRules.add(new BannedItem(
                                    format,
                                    CardDbAdapter.getStringFromCursor(cursor, CardDbAdapter.KEY_LEGALITY),
                                    CardDbAdapter.getStringFromCursor(cursor, CardDbAdapter.KEY_BANNED_LIST), false));
                        } else if (isBanned) {
                            mRules.add(new BannedItem(
                                    CardDbAdapter.getStringFromCursor(cursor, CardDbAdapter.KEY_NAME),
                                    NONE, "", true));
                        } else {
                            mRules.add(new RuleItem(
                                    CardDbAdapter.getIntFromCursor(cursor, CardDbAdapter.KEY_CATEGORY),
                                    CardDbAdapter.getIntFromCursor(cursor, CardDbAdapter.KEY_SUBCATEGORY),
                                    CardDbAdapter.getStringFromCursor(cursor, CardDbAdapter.KEY_ENTRY),
                                    CardDbAdapter.getStringFromCursor(cursor, CardDbAdapter.KEY_RULE_TEXT)));
                        }

                        // If there is an rule to scroll to by entry, find it and note the position
                        if ((null != entryToScrollTo) && (mRules.get(mRules.size() - 1).getEntry().equals(entryToScrollTo))) {
                            positionToScrollTo = cursor.getPosition();
                        }

                        cursor.moveToNext();
                    }
                    if (!isGlossary && !isBanned && mCategory == -1 && keyword == null) {
                        /* If it's the initial rules page, add a Glossary link to the end*/
                        mRules.add(new GlossaryItem(getString(R.string.rules_glossary), "", true));
                        mRules.add(new BannedItem(getString(R.string.rules_banned_and_restricted), NONE, "", true));
                    }
                    int listItemResource = R.layout.rules_list_item;
                    /* These cases can't be exclusive; otherwise keyword search from anything but a subcategory will use
                     * the wrong layout*/
                    if (isGlossary || isBanned || mSubcategory >= 0 || keyword != null) {
                        listItemResource = R.layout.rules_list_detail_item;
                    }
                    RulesListAdapter adapter = new RulesListAdapter(getActivity(), listItemResource, mRules, isSearchResult);
                    list.setAdapter(adapter);

                    list.setOnItemClickListener((parent, view, position12, id) -> {
                        if (isClickable) {
                            /* This only happens for rule mItems with no subcategory, so the cast, should be safe */
                            DisplayItem item = mRules.get(position12);
                            Bundle args = new Bundle();
                            if (item instanceof RuleItem) {
                                args.putInt(CATEGORY_KEY, ((RuleItem) item).mCategory);
                                args.putInt(SUBCATEGORY_KEY, ((RuleItem) item).mSubcategory);
                            } else if (item instanceof GlossaryItem) {
                                args.putBoolean(GLOSSARY_KEY, true);
                            } else if (item instanceof BannedItem) {
                                args.putBoolean(BANNED_KEY, true);
                                if (isBanned) {
                                    args.putString(FORMAT_KEY, item.getHeader());
                                }
                            }
                            RulesFragment frag = new RulesFragment();
                            startNewFragment(frag, args);
                        } else if (isSearchResult) {
                            // If this is a search, tapping the rule jumps to the rule in context
                            DisplayItem item = mRules.get(position12);
                            if (item instanceof RuleItem) {
                                Bundle args = new Bundle();
                                args.putInt(CATEGORY_KEY, ((RuleItem) item).mCategory);
                                args.putInt(SUBCATEGORY_KEY, ((RuleItem) item).mSubcategory);
                                args.putString(ENTRY_KEY, ((RuleItem) item).mEntry);
                                RulesFragment frag = new RulesFragment();
                                startNewFragment(frag, args);
                            }
                        }
                    });
                    list.setOnItemLongClickListener((parent, view, position1, id) -> {
                        DisplayItem item = mRules.get(position1);
                        if (item instanceof RuleItem) {
                            // Gets a handle to the clipboard service.
                            ClipboardManager clipboard = (ClipboardManager)
                                    requireActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                            if (null != clipboard) {
                                // Creates a new text clip to put on the clipboard
                                ClipData clip = ClipData.newPlainText(getString(R.string.rules_copy_tag), item.getHeader() + ": " + item.getText());
                                // Set the clipboard's primary clip.
                                clipboard.setPrimaryClip(clip);
                                // Alert the user
                                SnackbarWrapper.makeAndShowText(getActivity(), R.string.rules_coppied, SnackbarWrapper.LENGTH_SHORT);
                            }
                        }
                        return true;
                    });
                } else {
                    /* Cursor had a size of 0, boring */
                    if (!isBanned) {
                        SnackbarWrapper.makeAndShowText(getActivity(), R.string.rules_no_results_toast, SnackbarWrapper.LENGTH_SHORT);
                        FragmentManager fm = requireFragmentManager();
                        if (!fm.isStateSaved()) {
                            fm.popBackStack();
                        }
                    }
                }
            } else {
                if (!isBanned) { /* Cursor is null. weird. */
                    SnackbarWrapper.makeAndShowText(getActivity(), R.string.rules_no_results_toast, SnackbarWrapper.LENGTH_SHORT);
                    FragmentManager fm = requireFragmentManager();
                    if (!fm.isStateSaved()) {
                        fm.popBackStack();
                    }
                }
            }

        } catch (SQLiteException | FamiliarDbException e) {
            handleFamiliarDbException(true);
            return myFragmentView;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            if (setsCursor != null) {
                setsCursor.close();
            }
            DatabaseManager.closeDatabase(getActivity(), handle);
        }

        // Set the position appropriately
        if (-1 != positionToScrollTo) {
            list.setSelection(positionToScrollTo);
        } else {
            list.setSelection(position);
        }

        /* Explanations for these regular expressions are available upon request. - Alex */
        mUnderscorePattern = Pattern.compile("_(.+?)_");
        mExamplePattern = Pattern.compile("(Example:.+)$");
        mGlyphPattern = Pattern.compile("\\{([a-zA-Z0-9/]{1,5})\\}");
        if (keyword != null && !keyword.contains("{") && !keyword.contains("}")) {
            mKeywordPattern = Pattern.compile("(" + Pattern.quote(keyword) + ")", Pattern.CASE_INSENSITIVE);
        } else {
            mKeywordPattern = null;
        }
        mHyperlinkPattern = Pattern.compile("<(http://)?(www|gatherer|mtgcommander)(.+?)>");

        /*
         * Regex breakdown for Adam:
         * [1-9]: first character is between 1 and 9
         * [0-9]{2}: followed by two characters between 0 and 9 (i.e. a 3-digit number)
         * (...)?: maybe followed by the group:
         * \\.: period
         * ([a-z0-9]{1,3}(-[a-z]{1})?)?: maybe followed by one to three alphanumeric
         * characters, which are maybe followed by a hyphen and an alphabetical
         * character \\.?: maybe followed by another period
         *
         * I realize this isn't completely easy to read, but it might at least help
         * make some sense of the regex so I'm not just waving my hands and shouting
         * "WIZARDS!". I still reserve the right to do that, though. - Alex
         */
        mLinkPattern = Pattern.compile("([1-9][0-9]{2}(\\.([a-z0-9]{1,4}(-[a-z])?)?\\.?)?)");

        return myFragmentView;
    }

    /**
     * Remove any showing dialogs, and show the requested one
     */
    private void showDialog() throws IllegalStateException {
        /* DialogFragment.show() will take care of adding the fragment in a transaction. We also want to remove any
        currently showing dialog, so make our own transaction and take care of that here. */

        /* If the fragment isn't visible (maybe being loaded by the pager), don't show dialogs */
        if (!this.isVisible()) {
            return;
        }

        removeDialog(getParentFragmentManager());

        /* Create and show the dialog. */
        RulesDialogFragment newFragment = new RulesDialogFragment();
        newFragment.show(getParentFragmentManager(), FamiliarActivity.DIALOG_TAG);
    }

    /**
     * Handle a click in the menu
     *
     * @param item The item clicked
     * @return true if the click was handled, false otherwise
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.rules_menu_exit) {
            FragmentManager fm = requireFragmentManager();
            if (!fm.isStateSaved()) {
                for (int i = 0; i < fm.getBackStackEntryCount(); ++i) {
                    fm.popBackStack();
                }
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Called when the user clicks the search key in the menu
     *
     * @return true, since the click was acted upon
     */
    @Override
    public boolean onInterceptSearchKey() {
        showDialog();
        return true;
    }

    /**
     * @return true, since this fragment can intercept the search key
     */
    @Override
    public boolean canInterceptSearchKey() {
        return true;
    }

    /**
     * Format text for an entry with glyphs and links to other entries, found using regular expressions
     * TODO is input.length() == 0? That makes SpannableString unhappy
     *
     * @param input      The entry to format
     * @param shouldLink true if links should be added, false otherwise
     * @return a SpannableString with glyphs and links
     */
    private SpannableString formatText(String input, boolean shouldLink, boolean hasCards) {
        if (null == input) {
            return new SpannableString("");
        }
        String encodedInput = input;
        encodedInput = mUnderscorePattern.matcher(encodedInput).replaceAll("\\<i\\>$1\\</i\\>");
        encodedInput = mExamplePattern.matcher(encodedInput).replaceAll("\\<i\\>$1\\</i\\>");
        encodedInput = mGlyphPattern.matcher(encodedInput).replaceAll("\\<img src=\"$1\"/\\>");
        if (mKeywordPattern != null) {
            encodedInput = mKeywordPattern.matcher(encodedInput)
                    .replaceAll("\\<b\\>\\<u\\>\\<font color=\"" +
                            String.format("0x%06X", 0xFFFFFF & ContextCompat.getColor(requireContext(), R.color.material_green_800)) +
                            "\"\\>$1\\</font\\>\\</u\\>\\</b\\>");
        }
        encodedInput = mHyperlinkPattern.matcher(encodedInput).replaceAll("\\<a href=\"http://$2$3\"\\>$2$3\\</a\\>");
        encodedInput = encodedInput.replace("{", "").replace("}", "");

        CharSequence cs = ImageGetterHelper
                .formatStringWithGlyphs(encodedInput, ImageGetterHelper.GlyphGetter(getActivity()));
        SpannableString result = new SpannableString(cs);

        if (shouldLink) {
            Matcher m = mLinkPattern.matcher(cs);
            while (m.find()) {
                FamiliarDbHandle handle = new FamiliarDbHandle();
                try {
                    SQLiteDatabase database = DatabaseManager.openDatabase(getActivity(), false, handle);
                    String[] tokens = cs.subSequence(m.start(), m.end()).toString().split("(\\.)");
                    int firstInt = Integer.parseInt(tokens[0]);
                    final int linkCat = firstInt / 100;
                    final int linkSub = firstInt % 100;
                    int position = 0;
                    if (tokens.length > 1) {
                        String entry = tokens[1];
                        int dashIndex = entry.indexOf("-");
                        if (dashIndex >= 0) {
                            entry = entry.substring(0, dashIndex);
                        }
                        position = CardDbAdapter.getRulePosition(linkCat, linkSub, entry, database);
                    }
                    final int linkPosition = position;
                    result.setSpan(new ClickableSpan() {
                        @Override
                        public void onClick(@NonNull View widget) {
                            /* Open a new activity instance*/
                            Bundle args = new Bundle();
                            args.putInt(CATEGORY_KEY, linkCat);
                            args.putInt(SUBCATEGORY_KEY, linkSub);
                            args.putInt(POSITION_KEY, linkPosition);
                            RulesFragment frag = new RulesFragment();
                            startNewFragment(frag, args);
                        }
                    }, m.start(), m.end(), 0);
                } catch (Exception e) {
                    /* Eat any exceptions; they'll just cause the link to not appear*/
                } finally {
                    DatabaseManager.closeDatabase(getActivity(), handle);
                }
            }
        }

        if (hasCards) {
            String[] cards = cs.toString().split(Pattern.quote("\n"));
            int indexEnd = 0;
            for (String cardName : cards) {
                int indexStart = result.toString().indexOf(cardName, indexEnd);
                indexEnd = indexStart + cardName.length();
                result.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        FamiliarDbHandle handle = new FamiliarDbHandle();
                        try {
                            SQLiteDatabase database = DatabaseManager.openDatabase(getActivity(), false, handle);
                            long cardId = CardDbAdapter.fetchIdByName(cardName, database);
                            Bundle args = new Bundle();
                            if (cardId > 0) {
                                args.putLongArray(
                                        CardViewPagerFragment.CARD_ID_ARRAY,
                                        new long[]{cardId}
                                );
                                args.putInt(CardViewPagerFragment.STARTING_CARD_POSITION, 0);
                                CardViewPagerFragment cvpFrag = new CardViewPagerFragment();
                                startNewFragment(cvpFrag, args);
                            }
                        } catch (Exception e) {
                            /* Just eat it */
                        } finally {
                            DatabaseManager.closeDatabase(getActivity(), handle);
                        }
                    }
                }, indexStart, indexEnd, 0);
            }
        }

        return result;
    }

    /**
     * @param menu     The options menu in which you place your mItems.
     * @param inflater The inflater to use to inflate the menu
     */
    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.rules_menu, menu);
    }

    /**
     * This is an abstract class which can be displayed with a RulesListAdapter in the fragment
     */
    private abstract static class DisplayItem {
        /**
         * @return The string text associated with this entry
         */
        protected abstract String getText();

        /**
         * @return The string header associated with this entry
         */
        protected abstract String getHeader();

        /**
         * @return True if clicking this entry opens a sub-fragment, false otherwise
         */
        protected abstract boolean isClickable();

        /**
         * @return The string entry associated with this entry
         */
        public abstract String getEntry();
    }

    /**
     * This is a rule to be displayed in the list view
     */
    private static class RuleItem extends DisplayItem {
        private final int mCategory;
        private final int mSubcategory;
        private final String mEntry;
        private final String mRulesText;

        /**
         * Constructor
         *
         * @param category    The integer category of the rule
         * @param subcategory The integer subcategory of the rule
         * @param entry       The letter entry of the rule.
         * @param rulesText   The rule. Follow it!
         */
        RuleItem(int category, int subcategory, String entry, String rulesText) {
            this.mCategory = category;
            this.mSubcategory = subcategory;
            this.mEntry = entry;
            this.mRulesText = rulesText;
        }

        /**
         * Returns the rules text to be shown in the list
         *
         * @return The rules text
         */
        public String getText() {
            return this.mRulesText;
        }

        /**
         * @return The entry without the category or subcategory
         */
        public String getEntry() {
            return this.mEntry;
        }

        /**
         * Returns the header to be shown in the list
         *
         * @return Some concatenation of the category, subcategory, and entry, depending on what is valid
         */
        public String getHeader() {
            if (this.mSubcategory == -1) {
                return this.mCategory + ".";
            } else if (this.mEntry == null) {
                return ((this.mCategory * 100) + this.mSubcategory) + ".";
            } else {
                return (this.mCategory * 100 + this.mSubcategory) + "." + this.mEntry;
            }
        }

        /**
         * This entry is clickable if it does not have rules text, i.e. it is just a header which will open a
         * sub-fragment
         *
         * @return True if it launches a sub-fragment, false otherwise
         */
        public boolean isClickable() {
            return this.mEntry == null || this.mEntry.length() == 0;
        }
    }

    /**
     * This is a glossary item to be displayed in the list view
     */
    private static class GlossaryItem extends DisplayItem {
        private final String mTerm;
        private final String mDefinition;
        private final boolean mClickable;

        /**
         * Constructor
         *
         * @param term       The term to be defined
         * @param definition The definition of the term
         * @param clickable  Whether clicking this entry will start a sub-fragment. In practice, just the main glossary
         *                   entry point
         */
        GlossaryItem(String term, String definition, boolean clickable) {
            this.mTerm = term;
            this.mDefinition = definition;
            this.mClickable = clickable;
        }

        /**
         * @return the definition for this glossary entry
         */
        public String getText() {
            return this.mDefinition;
        }

        /**
         * @return the header for this glossary entry
         */
        public String getHeader() {
            return this.mTerm;
        }

        /**
         * @return whether clicking this entry launches a sub fragment
         */
        public boolean isClickable() {
            return this.mClickable;
        }

        /**
         * @return The header, same as the entry
         */
        @Override
        public String getEntry() {
            return getHeader();
        }
    }

    private class BannedItem extends DisplayItem {
        private final String mFormat;
        private final String mLegality;
        private final String mCards;
        private final boolean mClickable;

        /**
         * Constructor
         *
         * @param format    The format
         * @param legality  The legality of the cards
         * @param cards     Banned and restricted cards in the format
         * @param clickable Whether clicking on this entry will start a sub-fragment. Main Banned entry point
         */
        BannedItem(String format, String legality, String cards, boolean clickable) {

            this.mFormat = format;

            if (("Reserved List".equals(this.mFormat)) && ("Banned".equals(legality))) {
                this.mLegality = getString(R.string.card_on_reserved_list);
            } else {
                this.mLegality = legality;
            }

            if (cards == null) {
                if ((null != legality) && legality.equals(SETS)) {
                    this.mCards = getString(R.string.rules_bb_wb_sets);
                } else {
                    this.mCards = getString(R.string.rules_no_cards);
                }
            } else {
                this.mCards = cards;
            }
            this.mClickable = clickable;
        }

        /**
         * @return the list of banned and restricted cards
         */
        public String getText() {
            return this.mCards;
        }

        /**
         * @return the format and legality
         */
        public String getHeader() {
            if (mClickable) {
                // it is the initial rules fragment
                return this.mFormat;
            } else {
                return this.mLegality;
            }
        }

        /**
         * @return whether clicking this entry launches a sub-fragment
         */
        public boolean isClickable() {
            return this.mClickable;
        }

        /**
         * @return The header, same as the entry
         */
        @Override
        public String getEntry() {
            return getHeader();
        }

        /**
         * @return whether this entry is a list of cards
         */
        boolean isListOfCards() {
            return mLegality.equals(getString(R.string.card_view_banned)) ||
                    mLegality.equals(getString(R.string.rules_banned_as_commander)) ||
                    mLegality.equals(getString(R.string.card_view_restricted)) ||
                    mLegality.equals(getString(R.string.card_on_reserved_list));
        }
    }

    /**
     * This class displays rules items in the list view. It also enables the fast scrolling with the alphabet
     */
    private class RulesListAdapter extends ArrayAdapter<DisplayItem> implements SectionIndexer {
        private final int mLayoutResourceId;
        private final ArrayList<DisplayItem> mItems;
        private final boolean mIsSearchResult;

        private Integer[] mIndices;
        private String[] mAlphabet;

        /**
         * Constructor
         *
         * @param context            A context to inflate views with
         * @param textViewResourceId The layout to inflate, either R.layout.rules_list_detail_item or
         *                           R.layout.rules_list_item
         * @param items              The DisplayItems to show
         */
        RulesListAdapter(Context context, int textViewResourceId, ArrayList<DisplayItem> items, boolean isSearchResult) {
            super(context, textViewResourceId, items);

            this.mLayoutResourceId = textViewResourceId;
            this.mItems = items;
            this.mIsSearchResult = isSearchResult;

            boolean isGlossary = true;
            for (DisplayItem item : items) {
                if (item instanceof RuleItem || item instanceof BannedItem) {
                    isGlossary = false;
                    break;
                }
            }

            /* Enable fast scrolling for the glossary. Add all the first letters of the entries */
            if (isGlossary) {
                LinkedHashSet<Integer> indicesLHS = new LinkedHashSet<>();
                LinkedHashSet<String> alphabetLHS = new LinkedHashSet<>();

                /* Find the first index for each letter in the alphabet by looking at all the items */
                for (int index = 0; index < items.size(); index++) {
                    String letter = items.get(index).getHeader().substring(0, 1).toUpperCase();
                    if (!alphabetLHS.contains(letter)) {
                        alphabetLHS.add(letter);
                        indicesLHS.add(index);
                    }
                }
                mAlphabet = new String[alphabetLHS.size()];
                mAlphabet = alphabetLHS.toArray(mAlphabet);
                mIndices = new Integer[indicesLHS.size()];
                mIndices = indicesLHS.toArray(mIndices);
            } else {
                this.mIndices = null;
                this.mAlphabet = null;
            }
        }

        /**
         * Get a view for a given item's position
         *
         * @param position    The position of the item to display
         * @param convertView A view. If not null, it should be updated with this position's info
         * @param parent      The parent this view will eventually be attached to
         * @return The view to display
         */
        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                LayoutInflater inf = requireActivity().getLayoutInflater();
                v = inf.inflate(mLayoutResourceId, parent, false);
            }
            assert v != null;
            DisplayItem data = mItems.get(position);
            if (data != null) {
                TextView rulesHeader = v.findViewById(R.id.rules_item_header);
                TextView rulesText = v.findViewById(R.id.rules_item_text);

                String header = data.getHeader();
                String text = data.getText();

                rulesHeader.setText(formatText(header, false, false), BufferType.SPANNABLE);
                if (text.equals("")) {
                    rulesText.setVisibility(View.GONE);
                } else {
                    boolean shouldLink = true;
                    boolean hasCards = false;
                    if (data instanceof BannedItem) {
                        shouldLink = false;
                        hasCards = ((BannedItem) data).isListOfCards();
                    }
                    rulesText.setVisibility(View.VISIBLE);
                    rulesText.setText(formatText(text, shouldLink, hasCards), BufferType.SPANNABLE);
                }

                // Set this entry as not clickable, which means links in text can be clicked
                if (!data.isClickable() && !this.mIsSearchResult) {
                    rulesText.setMovementMethod(LinkMovementMethod.getInstance());
                    rulesText.setClickable(false);
                }
            }
            return v;
        }

        /**
         * Given the index of a section within the array of section objects, returns the starting position of that
         * section within the adapter. If the section's starting position is outside of the adapter bounds, the position
         * must be clipped to fall within the size of the adapter.
         *
         * @param section the index of the section within the array of section objects
         * @return the starting position of that section within the adapter, constrained to fall within the adapter
         * bounds
         */
        public int getPositionForSection(int section) {
            if (this.mIndices == null) {
                return 0;
            } else {
                return mIndices[section];
            }
        }

        /**
         * Given a position within the adapter, returns the index of the corresponding section within the array of
         * section objects. If the section index is outside of the section array bounds, the index must be clipped to
         * fall within the size of the section array. For example, consider an indexer where the section at array index
         * 0 starts at adapter position 100. Calling this method with position 10, which is before the first section,
         * must return index 0.
         *
         * @param position the position within the adapter for which to return the corresponding section index
         * @return the index of the corresponding section within the array of section objects, constrained to fall
         * within the array bounds
         */
        public int getSectionForPosition(int position) {
            if (this.mIndices == null) {
                return 0;
            } else {
                return 1;
            }
        }

        /**
         * Returns an array of objects representing sections of the list. The returned array and its contents should be
         * non-null. The list view will call toString() on the objects to get the preview text to display while
         * scrolling. For example, an adapter may return an array of Strings representing letters of the alphabet. Or,
         * it may return an array of objects whose toString() methods return their section titles.
         *
         * @return the array of section objects
         */
        public Object[] getSections() {
            if (this.mIndices == null) {
                return null;
            } else {
                return mAlphabet;
            }
        }
    }
}
