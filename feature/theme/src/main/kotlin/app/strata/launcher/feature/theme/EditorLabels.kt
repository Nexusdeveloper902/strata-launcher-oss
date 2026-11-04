package app.strata.launcher.feature.theme

/** Label resources of the editor's groups, fields, colour roles and enum options (THEME_SYSTEM §4). */
internal object EditorLabels {
    fun group(key: String): Int? =
        when (key) {
            "about" -> R.string.editor_group_about
            "colors" -> R.string.editor_group_colors
            "wallpaper" -> R.string.editor_group_wallpaper
            "typography" -> R.string.editor_group_typography
            "list" -> R.string.editor_group_list
            "icons" -> R.string.editor_group_icons
            "clock" -> R.string.editor_group_clock
            "favorites" -> R.string.editor_group_favorites
            "search" -> R.string.editor_group_search
            "alphabetIndex" -> R.string.editor_group_alphabet_index
            "animation" -> R.string.editor_group_animation
            "haptics" -> R.string.editor_group_haptics
            else -> null
        }

    fun field(id: String): Int? =
        when (id) {
            "name" -> R.string.editor_field_name
            "author" -> R.string.editor_field_author
            "description" -> R.string.editor_field_description
            "colors.mode" -> R.string.editor_field_colors_mode
            "colors.source.kind" -> R.string.editor_field_colors_source_kind
            "colors.source.seedColor" -> R.string.editor_field_colors_source_seed_color
            "colors.pureBlack" -> R.string.editor_field_colors_pure_black
            "wallpaper.show" -> R.string.editor_field_wallpaper_show
            "wallpaper.dim" -> R.string.editor_field_wallpaper_dim
            "wallpaper.backgroundOpacity" -> R.string.editor_field_wallpaper_background_opacity
            "typography.scale" -> R.string.editor_field_typography_scale
            "typography.labelWeight" -> R.string.editor_field_typography_label_weight
            "typography.headingWeight" -> R.string.editor_field_typography_heading_weight
            "typography.clockWeight" -> R.string.editor_field_typography_clock_weight
            "typography.letterSpacingEm" -> R.string.editor_field_typography_letter_spacing_em
            "typography.labelCase" -> R.string.editor_field_typography_label_case
            "typography.smallTextUsesSystemFont" -> R.string.editor_field_typography_small_text_uses_system_font
            "list.density" -> R.string.editor_field_list_density
            "list.rowHeightDp" -> R.string.editor_field_list_row_height_dp
            "list.horizontalPaddingDp" -> R.string.editor_field_list_horizontal_padding_dp
            "list.itemSpacingDp" -> R.string.editor_field_list_item_spacing_dp
            "list.sectionHeaders" -> R.string.editor_field_list_section_headers
            "list.showDividers" -> R.string.editor_field_list_show_dividers
            "icons.visible" -> R.string.editor_field_icons_visible
            "icons.sizeDp" -> R.string.editor_field_icons_size_dp
            "icons.shape" -> R.string.editor_field_icons_shape
            "icons.cornerRadiusPercent" -> R.string.editor_field_icons_corner_radius_percent
            "icons.tint" -> R.string.editor_field_icons_tint
            "icons.legacyIconPlate" -> R.string.editor_field_icons_legacy_icon_plate
            "icons.labelsVisible" -> R.string.editor_field_icons_labels_visible
            "clock.visible" -> R.string.editor_field_clock_visible
            "clock.style" -> R.string.editor_field_clock_style
            "clock.hourFormat" -> R.string.editor_field_clock_hour_format
            "clock.showSeconds" -> R.string.editor_field_clock_show_seconds
            "clock.sizeSp" -> R.string.editor_field_clock_size_sp
            "clock.alignment" -> R.string.editor_field_clock_alignment
            "clock.date.visible" -> R.string.editor_field_clock_date_visible
            "clock.date.skeleton" -> R.string.editor_field_clock_date_skeleton
            "clock.date.sizeSp" -> R.string.editor_field_clock_date_size_sp
            "favorites.textScale" -> R.string.editor_field_favorites_text_scale
            "favorites.alignment" -> R.string.editor_field_favorites_alignment
            "favorites.verticalPosition" -> R.string.editor_field_favorites_vertical_position
            "favorites.showIcons" -> R.string.editor_field_favorites_show_icons
            "favorites.showLabels" -> R.string.editor_field_favorites_show_labels
            "favorites.spacingDp" -> R.string.editor_field_favorites_spacing_dp
            "search.position" -> R.string.editor_field_search_position
            "search.style" -> R.string.editor_field_search_style
            "search.cornerRadiusDp" -> R.string.editor_field_search_corner_radius_dp
            "search.showHint" -> R.string.editor_field_search_show_hint
            "alphabetIndex.visibility" -> R.string.editor_field_alphabet_index_visibility
            "alphabetIndex.side" -> R.string.editor_field_alphabet_index_side
            "alphabetIndex.glyphs" -> R.string.editor_field_alphabet_index_glyphs
            "alphabetIndex.textSizeSp" -> R.string.editor_field_alphabet_index_text_size_sp
            "alphabetIndex.showBubble" -> R.string.editor_field_alphabet_index_show_bubble
            "animation.durationScale" -> R.string.editor_field_animation_duration_scale
            "animation.listEntrance" -> R.string.editor_field_animation_list_entrance
            "haptics.mode" -> R.string.editor_field_haptics_mode
            else -> null
        }

    fun role(wire: String): Int? =
        when (wire) {
            "background" -> R.string.editor_role_background
            "surface" -> R.string.editor_role_surface
            "textPrimary" -> R.string.editor_role_text_primary
            "textSecondary" -> R.string.editor_role_text_secondary
            "accent" -> R.string.editor_role_accent
            "onAccent" -> R.string.editor_role_on_accent
            "divider" -> R.string.editor_role_divider
            "highlight" -> R.string.editor_role_highlight
            "scrim" -> R.string.editor_role_scrim
            else -> null
        }

    fun option(wire: String): Int? =
        when (wire) {
            "system" -> R.string.editor_opt_system
            "light" -> R.string.editor_opt_light
            "dark" -> R.string.editor_opt_dark
            "wallpaper" -> R.string.editor_opt_wallpaper
            "fixed" -> R.string.editor_opt_fixed
            "seed" -> R.string.editor_opt_seed
            "dynamic" -> R.string.editor_opt_dynamic
            "asIs" -> R.string.editor_opt_as_is
            "lower" -> R.string.editor_opt_lower
            "upper" -> R.string.editor_opt_upper
            "title" -> R.string.editor_opt_title
            "compact" -> R.string.editor_opt_compact
            "comfortable" -> R.string.editor_opt_comfortable
            "spacious" -> R.string.editor_opt_spacious
            "circle" -> R.string.editor_opt_circle
            "squircle" -> R.string.editor_opt_squircle
            "roundedSquare" -> R.string.editor_opt_rounded_square
            "square" -> R.string.editor_opt_square
            "none" -> R.string.editor_opt_none
            "monochrome" -> R.string.editor_opt_monochrome
            "accent" -> R.string.editor_opt_accent
            "numeric" -> R.string.editor_opt_numeric
            "numericStacked" -> R.string.editor_opt_numeric_stacked
            "words" -> R.string.editor_opt_words
            "analogMinimal" -> R.string.editor_opt_analog_minimal
            "h12" -> R.string.editor_opt_h12
            "h24" -> R.string.editor_opt_h24
            "start" -> R.string.editor_opt_start
            "center" -> R.string.editor_opt_center
            "end" -> R.string.editor_opt_end
            "top" -> R.string.editor_opt_top
            "bottom" -> R.string.editor_opt_bottom
            "hidden" -> R.string.editor_opt_hidden
            "pill" -> R.string.editor_opt_pill
            "box" -> R.string.editor_opt_box
            "underline" -> R.string.editor_opt_underline
            "always" -> R.string.editor_opt_always
            "listOnly" -> R.string.editor_opt_list_only
            "never" -> R.string.editor_opt_never
            "letters" -> R.string.editor_opt_letters
            "dots" -> R.string.editor_opt_dots
            "fade" -> R.string.editor_opt_fade
            "slide" -> R.string.editor_opt_slide
            "followSystem" -> R.string.editor_opt_follow_system
            "off" -> R.string.editor_opt_off
            else -> null
        }
}
