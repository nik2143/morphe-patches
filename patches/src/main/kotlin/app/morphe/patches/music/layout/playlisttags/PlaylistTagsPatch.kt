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
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
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

            // The loop exits by branching to the return instruction, so the label must move to the hook.
            addInstructionsAtControlFlowLabel(
                returnIndex,
                """
                    invoke-static/range { v$listRegister .. v$listRegister }, $EXTENSION_CLASS->filterLibraryItems(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$listRegister
                """
            )
        }

        // Rebind the Library after the filter changes. Requesting a reload from the server
        // does nothing when the Library has no pending request, so bind the grid renderer the
        // presenter already holds, the same way it binds a new response.
        LibraryReloadShelfFingerprint.let {
            val itemsMethod = GridRendererItemsFingerprint.method
            val gridRendererType = itemsMethod.parameterTypes.first().toString()

            it.classDef.apply {
                val bindMethod = methods.firstOrNull { method ->
                    method.returnType == "V" &&
                            method.parameterTypes.map { type -> type.toString() } == listOf(gridRendererType, "Z") &&
                            method.instructions.any { instruction ->
                                instruction.opcode == Opcode.INVOKE_STATIC &&
                                        instruction.getReference<MethodReference>()?.let { reference ->
                                            reference.definingClass == itemsMethod.definingClass &&
                                                    reference.name == itemsMethod.name
                                        } == true
                            }
                } ?: throw PatchException("Could not find the Library bind method")

                val gridRendererField = bindMethod.instructions.firstNotNullOfOrNull { instruction ->
                    if (instruction.opcode != Opcode.IPUT_OBJECT) return@firstNotNullOfOrNull null
                    instruction.getReference<FieldReference>()?.takeIf { reference ->
                        reference.definingClass == type && reference.type == gridRendererType
                    }
                } ?: throw PatchException("Could not find the Library grid renderer field")

                // The bind method keeps the items in a field, which a recreated presenter restores.
                val itemsCallIndex = bindMethod.instructions.indexOfFirst { instruction ->
                    instruction.opcode == Opcode.INVOKE_STATIC &&
                            instruction.getReference<MethodReference>()?.name == itemsMethod.name
                }
                val itemsListField = bindMethod.instructions.drop(itemsCallIndex).firstNotNullOfOrNull { instruction ->
                    if (instruction.opcode != Opcode.IPUT_OBJECT) return@firstNotNullOfOrNull null
                    instruction.getReference<FieldReference>()?.takeIf { reference ->
                        reference.definingClass == type && reference.type == "Ljava/util/List;"
                    }
                } ?: throw PatchException("Could not find the Library items field")

                interfaces.add(EXTENSION_LIBRARY_PRESENTER_INTERFACE)
                addInterfaceMethod(
                    findInterfaceMethod(EXTENSION_LIBRARY_PRESENTER_INTERFACE, "patch_reloadLibrary"),
                    3,
                    """
                        iget-object v0, p0, $gridRendererField
                        if-eqz v0, :no_grid_renderer
                        const/4 v1, 0x1
                        invoke-direct { p0, v0, v1 }, $type->${bindMethod.name}(${gridRendererType}Z)V
                        :no_grid_renderer
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

                    // Filter the items restored from the saved state of a previous presenter.
                    // Added from the last one, so the indexes of the earlier ones stay valid.
                    constructor.instructions.withIndex().filter { (_, instruction) ->
                        instruction.opcode == Opcode.IPUT_OBJECT &&
                                instruction.getReference<FieldReference>() == itemsListField
                    }.map { it.index }.reversed().forEach { index ->
                        val listRegister = constructor.getInstruction<TwoRegisterInstruction>(index).registerA
                        constructor.addInstructions(
                            index,
                            """
                                invoke-static/range { v$listRegister .. v$listRegister }, $EXTENSION_CLASS->filterLibraryItems(Ljava/util/List;)Ljava/util/List;
                                move-result-object v$listRegister
                            """
                        )
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
