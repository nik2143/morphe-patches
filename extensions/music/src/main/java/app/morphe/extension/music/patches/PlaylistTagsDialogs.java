/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Dialogs to tag a playlist, to filter the Library by tag, and to manage tags.
 */
public final class PlaylistTagsDialogs {

    private PlaylistTagsDialogs() {
    }

    // region Tag a playlist

    /**
     * Shows the tags of a playlist as checkboxes, with a field to create a new tag.
     *
     * @param onDone Called after the tags were saved or the dialog was cancelled.
     */
    public static void showAssignDialog(Context context, String playlistId, @Nullable Runnable onDone) {
        try {
            Set<String> selected = new LinkedHashSet<>(PlaylistTagsStore.getPlaylistTags(playlistId));

            LinearLayout content = createContentLayout(context);
            LinearLayout checkboxes = new LinearLayout(context);
            checkboxes.setOrientation(LinearLayout.VERTICAL);
            content.addView(checkboxes);
            for (String tag : PlaylistTagsStore.getTags()) {
                checkboxes.addView(createTagCheckBox(context, tag, selected));
            }

            // New tag field and its add button.
            LinearLayout newTagRow = new LinearLayout(context);
            newTagRow.setOrientation(LinearLayout.HORIZONTAL);
            EditText newTagText = CustomDialog.createEditText(context);
            newTagText.setHint(str("morphe_music_playlist_tags_new_tag_hint"));
            newTagText.setSingleLine(true);
            newTagRow.addView(newTagText, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            Button addButton = CustomDialog.createButton(context, null,
                    str("morphe_music_playlist_tags_add"), null, false, false);
            addButton.setOnClickListener(v -> {
                String name = PlaylistTagsStore.normalizeTagName(newTagText.getText().toString());
                if (name.isEmpty()) return;
                if (!PlaylistTagsStore.addTag(name)) {
                    Utils.showToastShort(str("morphe_music_playlist_tags_tag_exists", name));
                    return;
                }
                selected.add(name);
                checkboxes.addView(createTagCheckBox(context, name, selected));
                newTagText.setText("");
            });
            LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            addParams.setMarginStart(Dim.dp8);
            newTagRow.addView(addButton, addParams);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = Dim.dp8;
            content.addView(newTagRow, rowParams);

            Pair<Dialog, LinearLayout> dialog = createDialog(context,
                    str("morphe_music_playlist_tags_assign_title",
                            PlaylistTagsStore.getPlaylistTitle(playlistId)),
                    content,
                    () -> {
                        // A typed but not added tag is added on save.
                        String typed = PlaylistTagsStore.normalizeTagName(newTagText.getText().toString());
                        if (!typed.isEmpty()) {
                            PlaylistTagsStore.addTag(typed);
                            selected.add(typed);
                        }

                        PlaylistTagsStore.setPlaylistTags(playlistId, selected);
                        PlaylistTagsPatch.reloadLibrary();
                        if (onDone != null) onDone.run();
                    },
                    () -> {
                        if (onDone != null) onDone.run();
                    },
                    null, null);
            dialog.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showAssignDialog failure", ex);
        }
    }

    private static CheckBox createTagCheckBox(Context context, String tag, Set<String> selected) {
        CheckBox checkBox = new CheckBox(context);
        checkBox.setText(tag);
        checkBox.setTextColor(ThemeUtils.getAppForegroundColor());
        checkBox.setButtonTintList(ColorStateList.valueOf(ThemeUtils.getAppForegroundColor()));
        checkBox.setChecked(selected.contains(tag));
        checkBox.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                selected.add(tag);
            } else {
                selected.remove(tag);
            }
        });
        return checkBox;
    }

    // endregion

    // region Filter the Library

    /**
     * Shows which tags are visible, and optionally a single tag to show only.
     *
     * @param onChanged Called if the filter changed.
     */
    public static void showFilterDialog(Context context, Runnable onChanged) {
        try {
            List<String> tags = PlaylistTagsStore.getTags();
            if (tags.isEmpty()) {
                Utils.showToastLong(str("morphe_music_playlist_tags_none"));
                return;
            }

            final String originalOnlyTag = PlaylistTagsStore.getOnlyTag();
            Map<String, Boolean> visibility = new LinkedHashMap<>();
            for (String tag : tags) {
                visibility.put(tag, !PlaylistTagsStore.isTagHidden(tag));
            }
            final String[] onlyTag = {originalOnlyTag};

            LinearLayout content = createContentLayout(context);

            // Show only one tag.
            content.addView(createSectionHeader(context, str("morphe_music_playlist_tags_filter_show_only")));
            RadioGroup radioGroup = new RadioGroup(context);
            radioGroup.addView(createRadioButton(context, str("morphe_music_playlist_tags_filter_all"),
                    originalOnlyTag.isEmpty(), () -> onlyTag[0] = ""));
            for (String tag : tags) {
                radioGroup.addView(createRadioButton(context, tag,
                        tag.equals(originalOnlyTag), () -> onlyTag[0] = tag));
            }
            content.addView(radioGroup);

            // Visible tags.
            content.addView(createSectionHeader(context, str("morphe_music_playlist_tags_filter_visible")));
            for (String tag : tags) {
                Switch tagSwitch = new Switch(context);
                tagSwitch.setText(tag);
                tagSwitch.setTextColor(ThemeUtils.getAppForegroundColor());
                tagSwitch.setMinHeight(Dim.dp(48));
                tagSwitch.setChecked(Boolean.TRUE.equals(visibility.get(tag)));
                tagSwitch.setOnCheckedChangeListener((button, checked) -> visibility.put(tag, checked));
                content.addView(tagSwitch);
            }

            Pair<Dialog, LinearLayout> dialog = createDialog(context,
                    str("morphe_music_playlist_tags_filter_title"),
                    content,
                    () -> {
                        boolean changed = !onlyTag[0].equals(originalOnlyTag);
                        PlaylistTagsStore.setOnlyTag(onlyTag[0]);
                        for (Map.Entry<String, Boolean> entry : visibility.entrySet()) {
                            boolean hidden = !entry.getValue();
                            if (PlaylistTagsStore.isTagHidden(entry.getKey()) != hidden) {
                                PlaylistTagsStore.setTagHidden(entry.getKey(), hidden);
                                changed = true;
                            }
                        }
                        if (changed) onChanged.run();
                    },
                    () -> {},
                    str("morphe_music_playlist_tags_manage"),
                    () -> showManagerDialog(context));
            dialog.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showFilterDialog failure", ex);
        }
    }

    private static RadioButton createRadioButton(Context context, String text, boolean checked, Runnable onChecked) {
        RadioButton button = new RadioButton(context);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setTextColor(ThemeUtils.getAppForegroundColor());
        button.setButtonTintList(ColorStateList.valueOf(ThemeUtils.getAppForegroundColor()));
        button.setChecked(checked);
        button.setOnCheckedChangeListener((view, isChecked) -> {
            if (isChecked) onChecked.run();
        });
        return button;
    }

    // endregion

    // region Manage tags

    /**
     * Lists all tags, to rename or delete them, and the playlists seen in the Library, to tag them.
     */
    public static void showManagerDialog(Context context) {
        try {
            LinearLayout content = createContentLayout(context);
            final Dialog[] managerDialog = new Dialog[1];
            Runnable reopen = () -> {
                if (managerDialog[0] != null) managerDialog[0].dismiss();
                showManagerDialog(context);
            };

            // Tags.
            content.addView(createSectionHeader(context, str("morphe_music_playlist_tags_section_tags")));
            List<String> tags = PlaylistTagsStore.getTags();
            for (String tag : tags) {
                String summary = PlaylistTagsStore.isTagHidden(tag)
                        ? str("morphe_music_playlist_tags_hidden")
                        : null;
                content.addView(createListRow(context, tag, summary,
                        () -> showEditTagDialog(context, tag, reopen)));
            }
            content.addView(createListRow(context, str("morphe_music_playlist_tags_new_tag"), null,
                    () -> showNewTagDialog(context, reopen)));

            // Playlists, tagged first.
            content.addView(createSectionHeader(context, str("morphe_music_playlist_tags_section_playlists")));
            Map<String, String> playlists = PlaylistTagsStore.getKnownPlaylists();
            if (playlists.isEmpty()) {
                TextView empty = createText(context, str("morphe_music_playlist_tags_no_playlists"), 14);
                empty.setAlpha(0.7f);
                content.addView(empty);
            } else {
                List<Map.Entry<String, String>> sorted = new ArrayList<>(playlists.entrySet());
                sorted.sort((a, b) -> {
                    boolean aTagged = !PlaylistTagsStore.getPlaylistTags(a.getKey()).isEmpty();
                    boolean bTagged = !PlaylistTagsStore.getPlaylistTags(b.getKey()).isEmpty();
                    if (aTagged != bTagged) return aTagged ? -1 : 1;
                    return a.getValue().compareToIgnoreCase(b.getValue());
                });
                for (Map.Entry<String, String> playlist : sorted) {
                    Set<String> playlistTags = PlaylistTagsStore.getPlaylistTags(playlist.getKey());
                    String summary = playlistTags.isEmpty()
                            ? str("morphe_music_playlist_tags_menu_item_none")
                            : TextUtils.join(", ", playlistTags);
                    content.addView(createListRow(context, playlist.getValue(), summary,
                            () -> {
                                managerDialog[0].dismiss();
                                showAssignDialog(context, playlist.getKey(), () -> showManagerDialog(context));
                            }));
                }
            }

            Pair<Dialog, LinearLayout> dialog = createDialog(context,
                    str("morphe_music_playlist_tags_manage_title"),
                    content, () -> {}, null, null, null);
            managerDialog[0] = dialog.first;
            dialog.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showManagerDialog failure", ex);
        }
    }

    private static void showNewTagDialog(Context context, Runnable onDone) {
        EditText editText = CustomDialog.createEditText(context);
        editText.setSingleLine(true);
        editText.setHint(str("morphe_music_playlist_tags_new_tag_hint"));

        Pair<Dialog, LinearLayout> dialog = CustomDialog.create(context,
                str("morphe_music_playlist_tags_new_tag"), null, editText, null,
                () -> {
                    String name = PlaylistTagsStore.normalizeTagName(editText.getText().toString());
                    if (name.isEmpty()) return;
                    if (!PlaylistTagsStore.addTag(name)) {
                        Utils.showToastShort(str("morphe_music_playlist_tags_tag_exists", name));
                        return;
                    }
                    onDone.run();
                },
                () -> {}, null, null, false);
        dialog.first.show();
    }

    private static void showEditTagDialog(Context context, String tag, Runnable onDone) {
        EditText editText = CustomDialog.createEditText(context);
        editText.setSingleLine(true);
        editText.setText(tag);

        Pair<Dialog, LinearLayout> dialog = CustomDialog.create(context,
                str("morphe_music_playlist_tags_edit_tag_title"), null, editText,
                str("morphe_music_playlist_tags_rename"),
                () -> {
                    String name = PlaylistTagsStore.normalizeTagName(editText.getText().toString());
                    if (!PlaylistTagsStore.renameTag(tag, name)) {
                        Utils.showToastShort(str("morphe_music_playlist_tags_tag_exists", name));
                        return;
                    }
                    PlaylistTagsPatch.reloadLibrary();
                    onDone.run();
                },
                () -> {},
                str("morphe_music_playlist_tags_delete"),
                () -> {
                    PlaylistTagsStore.deleteTag(tag);
                    PlaylistTagsPatch.reloadLibrary();
                    onDone.run();
                },
                true);
        dialog.first.show();
    }

    // endregion

    // region Views

    private static Pair<Dialog, LinearLayout> createDialog(Context context, String title, View content,
                                                           Runnable onOk, @Nullable Runnable onCancel,
                                                           @Nullable String neutralText,
                                                           @Nullable Runnable onNeutral) {
        Pair<Dialog, LinearLayout> dialog = CustomDialog.create(context, title, null, null, null,
                onOk, onCancel, neutralText, onNeutral, true);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        // Limit the height, so the buttons stay on screen with many tags or playlists.
        content.measure(View.MeasureSpec.makeMeasureSpec(Dim.pctWidth(80), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.UNSPECIFIED);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(content.getMeasuredHeight(), Dim.pctHeight(55)));
        // Insert before the button row.
        dialog.second.addView(scroll, dialog.second.getChildCount() - 1, scrollParams);
        return dialog;
    }

    private static LinearLayout createContentLayout(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Dim.dp4, 0, Dim.dp8);
        return content;
    }

    private static TextView createSectionHeader(Context context, String text) {
        TextView header = createText(context, text, 14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, Dim.dp12, 0, Dim.dp4);
        return header;
    }

    private static TextView createText(Context context, String text, int sizeSp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(ThemeUtils.getAppForegroundColor());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        return view;
    }

    private static View createListRow(Context context, String title, @Nullable String summary, Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(Dim.dp(48));
        row.setPadding(0, Dim.dp8, 0, Dim.dp8);
        row.setOnClickListener(v -> onClick.run());

        TextView titleView = createText(context, title, 16);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(titleView);

        if (summary != null) {
            TextView summaryView = createText(context, summary, 13);
            summaryView.setAlpha(0.7f);
            summaryView.setSingleLine(true);
            summaryView.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(summaryView);
        }
        return row;
    }

    // endregion
}
