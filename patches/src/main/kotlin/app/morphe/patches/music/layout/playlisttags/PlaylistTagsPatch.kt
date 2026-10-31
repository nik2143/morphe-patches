/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.playlisttags

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.androidauto.GridRendererItemsFingerprint
import app.morphe.patches.music.misc.androidauto.addInterfaceMethod
import app.morphe.patches.music.misc.androidauto.findInterfaceMethod
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/PlaylistTagsPatch;"
private const val EXTENSION_LIBRARY_PRESENTER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/PlaylistTagsPatch$LibraryPresenter;"

@Suppress("unused")
val playlistTagsPatch = bytecodePatch(
    name = "Playlist tags",
    description = "Adds an option to tag playlists from their menu, and to filter the Library playlists by tag.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_playlist_tags_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_music_playlist_tags", summary = true),
                    NonInteractivePreference(
                        key = "morphe_music_playlist_tags_manage",
                        tag = "app.morphe.extension.music.settings.preference.PlaylistTagsPreference",
                        selectable = true,
                        dependency = "morphe_music_playlist_tags",
                    ),
                )
            )
        )

        // region Library grid: hide the playlists of hidden tags.

        GridRendererItemsFingerprint.method.apply {
            val returnIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            val listRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA

            addInstructions(
                returnIndex,
                """
                    invoke-static/range { v$listRegister .. v$listRegister }, $EXTENSION_CLASS->filterLibraryItems(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$listRegister
                """
            )
        }

        // Reload the Library after the filter changes.
        LibraryReloadShelfFingerprint.let {
            val reloadMethod = it.instructionMatches.first().instruction.getReference<MethodReference>()!!

            it.classDef.apply {
                interfaces.add(EXTENSION_LIBRARY_PRESENTER_INTERFACE)
                addInterfaceMethod(
                    findInterfaceMethod(EXTENSION_LIBRARY_PRESENTER_INTERFACE, "patch_reloadLibrary"),
                    1,
                    """
                        invoke-virtual { p0 }, $reloadMethod
                        return-void
                    """
                )

                // The constructor reuses p0 as a scratch register after calling the super constructor,
                // so register the presenter right after that call, using the register it was called with.
                val superclassType = superclass
                methods.filter { method -> method.name == "<init>" }.forEach { constructor ->
                    val superInitIndex = constructor.instructions.indexOfFirst { instruction ->
                        (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE)
                                && instruction.getReference<MethodReference>()?.let { reference ->
                                    reference.name == "<init>" && reference.definingClass == superclassType
                                } == true
                    }
                    if (superInitIndex < 0) throw PatchException("Could not find the super constructor call")

                    val superInit = constructor.getInstruction<Instruction>(superInitIndex)
                    val thisRegister = if (superInit is RegisterRangeInstruction) {
                        superInit.startRegister
                    } else {
                        (superInit as FiveRegisterInstruction).registerC
                    }

                    constructor.addInstruction(
                        superInitIndex + 1,
                        "invoke-static/range { v$thisRegister .. v$thisRegister }, " +
                                "$EXTENSION_CLASS->registerLibraryPresenter(Ljava/lang/Object;)V"
                    )
                }
            }
        }

        // endregion

        // region Flyout menu: add a 'Tags' row to the menu of a playlist.

        val menuField = MenuBottomSheetItemsFingerprint.instructionMatches.first()
            .instruction.getReference<FieldReference>()!!

        MenuBottomSheetOnStartFingerprint.let {
            it.method.apply {
                val dialogIndex = it.instructionMatches.first().index
                val dialogRegister = getInstruction<TwoRegisterInstruction>(dialogIndex).registerA
                val insertIndex = dialogIndex + 1
                val menuRegister = findFreeRegister(insertIndex, dialogRegister)

                addInstructions(
                    insertIndex,
                    """
                        iget-object v$menuRegister, p0, $menuField
                        invoke-static { v$dialogRegister, v$menuRegister }, $EXTENSION_CLASS->onMenuBottomSheetStart(Landroid/app/Dialog;Ljava/lang/Object;)V
                    """
                )
            }
        }

        // endregion

        // region Library: add a tag filter button next to the sort button.

        SortFilterButtonConstructorFingerprint.let {
            it.method.apply {
                val viewIndex = it.instructionMatches.last().index
                val viewRegister = getInstruction<OneRegisterInstruction>(viewIndex).registerA

                addInstruction(
                    viewIndex + 1,
                    "invoke-static/range { v$viewRegister .. v$viewRegister }, " +
                            "$EXTENSION_CLASS->onSortFilterButtonCreated(Landroid/view/View;)V"
                )
            }
        }

        // endregion
    }
}
