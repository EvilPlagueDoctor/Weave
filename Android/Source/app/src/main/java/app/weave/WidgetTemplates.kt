package app.weave

/**
 * Source-only widgets bundled with Weave.
 *
 * Built-ins use the same parser/compiler/verifier as user-authored widgets. There is no trusted
 * native implementation hidden behind a template. Online templates use only the constrained
 * Public Widget Networking v1 host surface.
 */
enum class WidgetTemplateReadiness { Ready, HostScaffold, LanguageScaffold }

data class WidgetTemplate(
    val id: String,
    val name: String,
    val description: String,
    val readiness: WidgetTemplateReadiness,
    val source: String,
)

object WidgetTemplateCatalog {
    val all: List<WidgetTemplate> = listOf(
        WidgetTemplate(
            id = "clock",
            name = "Clock",
            description = "A local digital clock using the safe coarse Time capability.",
            readiness = WidgetTemplateReadiness.Ready,
            source = """widget "Clock":
    default_width = 320
    default_height = 170
    warn_on_resize = false
    background = "#F7F3F4"

    text title:
        x = 8%
        y = 10%
        width = 84%
        height = 20%
        text = "Local time"
        size = 16
        color = "#6B4650"
        align = center

    text clock:
        x = 6%
        y = 32%
        width = 88%
        height = 45%
        text = "--:--:--"
        size = 38
        color = "#20242A"
        bold = true
        align = center

    every 1 second:
        clock.text = time.format("HH:mm:ss")
"""
        ),
        WidgetTemplate(
            id = "playlist",
            name = "My playlist",
            description = "Local playlist UI scaffold for audio explicitly saved from profiles. Saved-audio host controls are not enabled yet.",
            readiness = WidgetTemplateReadiness.HostScaffold,
            source = """widget "My Playlist":
    default_width = 420
    default_height = 250
    warn_on_resize = true
    background = "#FBF7F8"

    text heading:
        x = 6%
        y = 6%
        width = 88%
        height = 16%
        text = "My playlist"
        size = 22
        color = "#7D3440"
        bold = true
        align = left

    text song:
        x = 8%
        y = 30%
        width = 84%
        height = 20%
        text = "No saved song selected"
        size = 16
        color = "#302B30"
        align = center

    button play:
        x = 31%
        y = 57%
        width = 38%
        height = 16%
        text = "Play / pause"
        size = 14
        color = "#FFFFFF"
        background = "#7D3440"
        align = center

    text note:
        x = 8%
        y = 79%
        width = 84%
        height = 13%
        text = "Saved-audio host controls are a future capability."
        size = 12
        color = "#5E5960"
        align = center

    on tap(play):
        song.text = "Playlist host capability is not enabled yet."
"""
        ),
        WidgetTemplate(
            id = "chess",
            name = "Chess",
            description = "Full turn-based Chess: 64-square board, legal-move enforcement, castling, en passant, promotion, check/checkmate/stalemate, draw rules, resignation, spectators, exact-action acknowledgement, and fair commit/reveal colour assignment.",
            readiness = WidgetTemplateReadiness.Ready,
            source = """widget "Chess":
    default_width = 500
    default_height = 760
    warn_on_resize = true
    background = "#E9E3DC"
    online = public
    input kind = number(1, 3)
    input value = number(0, 63)
    input resign = button

    state board = array(64, 0)
    state history = array(128, -1)
    state game_active = false
    state session_ready = false
    state invite_pending = false
    state my_player = 0
    state white_player = 0
    state my_colour = 0
    state turn_colour = 1
    state selected_from = -1
    state pending_to = -1
    state old_selected = -1
    state last_from = -1
    state last_to = -1
    state tap_send = false
    state promotion_pending = false
    state legal = false
    state pseudo = false
    state check_found = false
    state in_check = false
    state any_legal = false
    state insufficient = false
    state king_square = -1
    state white_king_square = 4
    state black_king_square = 60
    state path_ok = false
    state path_done = false
    state ray_blocked = false
    state piece = 0
    state target = 0
    state piece_type = 0
    state fx = 0
    state fy = 0
    state tx = 0
    state ty = 0
    state dx = 0
    state dy = 0
    state stepx = 0
    state stepy = 0
    state tmpx = 0
    state tmpy = 0
    state save_from_piece = 0
    state save_to_piece = 0
    state save_ep_idx = -1
    state save_ep_piece = 0
    state save_rook_from = -1
    state save_rook_to = -1
    state save_rook_from_piece = 0
    state save_rook_to_piece = 0
    state save_mid_piece = 0
    state promotion_piece = 0
    state ep_square = -1
    state halfmove = 0
    state white_king_moved = false
    state black_king_moved = false
    state white_rook_a_moved = false
    state white_rook_h_moved = false
    state black_rook_a_moved = false
    state black_rook_h_moved = false
    state candidate_promo = 0
    state expected_player = 0
    state incoming_valid = false
    state incoming_from = -1
    state incoming_to = -1
    state incoming_promo = 0
    state position_hash = 0
    state history_count = 0
    state repetition_count = 0
    state material_major = false
    state bishop_count = 0
    state knight_count = 0
    state bishop_light = 0
    state bishop_dark = 0
    state piece_text = "·"

    function clear_history_a():
        history[0] = -1
        history[1] = -1
        history[2] = -1
        history[3] = -1
        history[4] = -1
        history[5] = -1
        history[6] = -1
        history[7] = -1
        history[8] = -1
        history[9] = -1
        history[10] = -1
        history[11] = -1
        history[12] = -1
        history[13] = -1
        history[14] = -1
        history[15] = -1
        history[16] = -1
        history[17] = -1
        history[18] = -1
        history[19] = -1
        history[20] = -1
        history[21] = -1
        history[22] = -1
        history[23] = -1
        history[24] = -1
        history[25] = -1
        history[26] = -1
        history[27] = -1
        history[28] = -1
        history[29] = -1
        history[30] = -1
        history[31] = -1
        history[32] = -1
        history[33] = -1
        history[34] = -1
        history[35] = -1
        history[36] = -1
        history[37] = -1
        history[38] = -1
        history[39] = -1
        history[40] = -1
        history[41] = -1
        history[42] = -1
        history[43] = -1
        history[44] = -1
        history[45] = -1
        history[46] = -1
        history[47] = -1
        history[48] = -1
        history[49] = -1
        history[50] = -1
        history[51] = -1
        history[52] = -1
        history[53] = -1
        history[54] = -1
        history[55] = -1
        history[56] = -1
        history[57] = -1
        history[58] = -1
        history[59] = -1
        history[60] = -1
        history[61] = -1
        history[62] = -1
        history[63] = -1

    function clear_history_b():
        history[64] = -1
        history[65] = -1
        history[66] = -1
        history[67] = -1
        history[68] = -1
        history[69] = -1
        history[70] = -1
        history[71] = -1
        history[72] = -1
        history[73] = -1
        history[74] = -1
        history[75] = -1
        history[76] = -1
        history[77] = -1
        history[78] = -1
        history[79] = -1
        history[80] = -1
        history[81] = -1
        history[82] = -1
        history[83] = -1
        history[84] = -1
        history[85] = -1
        history[86] = -1
        history[87] = -1
        history[88] = -1
        history[89] = -1
        history[90] = -1
        history[91] = -1
        history[92] = -1
        history[93] = -1
        history[94] = -1
        history[95] = -1
        history[96] = -1
        history[97] = -1
        history[98] = -1
        history[99] = -1
        history[100] = -1
        history[101] = -1
        history[102] = -1
        history[103] = -1
        history[104] = -1
        history[105] = -1
        history[106] = -1
        history[107] = -1
        history[108] = -1
        history[109] = -1
        history[110] = -1
        history[111] = -1
        history[112] = -1
        history[113] = -1
        history[114] = -1
        history[115] = -1
        history[116] = -1
        history[117] = -1
        history[118] = -1
        history[119] = -1
        history[120] = -1
        history[121] = -1
        history[122] = -1
        history[123] = -1
        history[124] = -1
        history[125] = -1
        history[126] = -1
        history[127] = -1

    function redraw_square(p_i):
        piece_text = "·"
        if board[p_i] == 1:
            piece_text = "♙"
        if board[p_i] == 2:
            piece_text = "♘"
        if board[p_i] == 3:
            piece_text = "♗"
        if board[p_i] == 4:
            piece_text = "♖"
        if board[p_i] == 5:
            piece_text = "♕"
        if board[p_i] == 6:
            piece_text = "♔"
        if board[p_i] == -1:
            piece_text = "♟"
        if board[p_i] == -2:
            piece_text = "♞"
        if board[p_i] == -3:
            piece_text = "♝"
        if board[p_i] == -4:
            piece_text = "♜"
        if board[p_i] == -5:
            piece_text = "♛"
        if board[p_i] == -6:
            piece_text = "♚"
        ui.text("sq" + p_i, piece_text)
        if ((p_i % 8) + (p_i / 8)) % 2 == 0:
            ui.background("sq" + p_i, 4290087011)
        else:
            ui.background("sq" + p_i, 4293974453)
        if p_i == last_from or p_i == last_to:
            ui.background("sq" + p_i, 4287150443)
        if p_i == selected_from:
            ui.background("sq" + p_i, 4294370667)
        if promotion_pending == true and p_i == pending_to:
            ui.background("sq" + p_i, 4289753296)

    function redraw_all():
        call redraw_square(0)
        call redraw_square(1)
        call redraw_square(2)
        call redraw_square(3)
        call redraw_square(4)
        call redraw_square(5)
        call redraw_square(6)
        call redraw_square(7)
        call redraw_square(8)
        call redraw_square(9)
        call redraw_square(10)
        call redraw_square(11)
        call redraw_square(12)
        call redraw_square(13)
        call redraw_square(14)
        call redraw_square(15)
        call redraw_square(16)
        call redraw_square(17)
        call redraw_square(18)
        call redraw_square(19)
        call redraw_square(20)
        call redraw_square(21)
        call redraw_square(22)
        call redraw_square(23)
        call redraw_square(24)
        call redraw_square(25)
        call redraw_square(26)
        call redraw_square(27)
        call redraw_square(28)
        call redraw_square(29)
        call redraw_square(30)
        call redraw_square(31)
        call redraw_square(32)
        call redraw_square(33)
        call redraw_square(34)
        call redraw_square(35)
        call redraw_square(36)
        call redraw_square(37)
        call redraw_square(38)
        call redraw_square(39)
        call redraw_square(40)
        call redraw_square(41)
        call redraw_square(42)
        call redraw_square(43)
        call redraw_square(44)
        call redraw_square(45)
        call redraw_square(46)
        call redraw_square(47)
        call redraw_square(48)
        call redraw_square(49)
        call redraw_square(50)
        call redraw_square(51)
        call redraw_square(52)
        call redraw_square(53)
        call redraw_square(54)
        call redraw_square(55)
        call redraw_square(56)
        call redraw_square(57)
        call redraw_square(58)
        call redraw_square(59)
        call redraw_square(60)
        call redraw_square(61)
        call redraw_square(62)
        call redraw_square(63)

    function setup_board():
        board[0] = 0
        board[1] = 0
        board[2] = 0
        board[3] = 0
        board[4] = 0
        board[5] = 0
        board[6] = 0
        board[7] = 0
        board[8] = 0
        board[9] = 0
        board[10] = 0
        board[11] = 0
        board[12] = 0
        board[13] = 0
        board[14] = 0
        board[15] = 0
        board[16] = 0
        board[17] = 0
        board[18] = 0
        board[19] = 0
        board[20] = 0
        board[21] = 0
        board[22] = 0
        board[23] = 0
        board[24] = 0
        board[25] = 0
        board[26] = 0
        board[27] = 0
        board[28] = 0
        board[29] = 0
        board[30] = 0
        board[31] = 0
        board[32] = 0
        board[33] = 0
        board[34] = 0
        board[35] = 0
        board[36] = 0
        board[37] = 0
        board[38] = 0
        board[39] = 0
        board[40] = 0
        board[41] = 0
        board[42] = 0
        board[43] = 0
        board[44] = 0
        board[45] = 0
        board[46] = 0
        board[47] = 0
        board[48] = 0
        board[49] = 0
        board[50] = 0
        board[51] = 0
        board[52] = 0
        board[53] = 0
        board[54] = 0
        board[55] = 0
        board[56] = 0
        board[57] = 0
        board[58] = 0
        board[59] = 0
        board[60] = 0
        board[61] = 0
        board[62] = 0
        board[63] = 0
        board[0] = 4
        board[1] = 2
        board[2] = 3
        board[3] = 5
        board[4] = 6
        board[5] = 3
        board[6] = 2
        board[7] = 4
        board[8] = 1
        board[9] = 1
        board[10] = 1
        board[11] = 1
        board[12] = 1
        board[13] = 1
        board[14] = 1
        board[15] = 1
        board[48] = -1
        board[49] = -1
        board[50] = -1
        board[51] = -1
        board[52] = -1
        board[53] = -1
        board[54] = -1
        board[55] = -1
        board[56] = -4
        board[57] = -2
        board[58] = -3
        board[59] = -5
        board[60] = -6
        board[61] = -3
        board[62] = -2
        board[63] = -4
        call clear_history_a()
        call clear_history_b()
        history_count = 0
        repetition_count = 0
        position_hash = 0
        ep_square = -1
        halfmove = 0
        turn_colour = 1
        white_king_square = 4
        black_king_square = 60
        selected_from = -1
        pending_to = -1
        last_from = -1
        last_to = -1
        tap_send = false
        promotion_pending = false
        white_king_moved = false
        black_king_moved = false
        white_rook_a_moved = false
        white_rook_h_moved = false
        black_rook_a_moved = false
        black_rook_h_moved = false
        promote_q.visible = false
        promote_r.visible = false
        promote_b.visible = false
        promote_n.visible = false
        call redraw_all()

    function path_clear(p_fx, p_fy, p_tx, p_ty):
        path_ok = true
        path_done = false
        stepx = 0
        stepy = 0
        if p_tx > p_fx:
            stepx = 1
        if p_tx < p_fx:
            stepx = 0 - 1
        if p_ty > p_fy:
            stepy = 1
        if p_ty < p_fy:
            stepy = 0 - 1
        if path_ok == true and path_done == false:
            tmpx = p_fx + stepx * 1
            tmpy = p_fy + stepy * 1
            if tmpx == p_tx and tmpy == p_ty:
                path_done = true
            else:
                if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                    path_ok = false
                else:
                    if board[(tmpy - 1) * 8 + (tmpx - 1)] != 0:
                        path_ok = false
        if path_ok == true and path_done == false:
            tmpx = p_fx + stepx * 2
            tmpy = p_fy + stepy * 2
            if tmpx == p_tx and tmpy == p_ty:
                path_done = true
            else:
                if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                    path_ok = false
                else:
                    if board[(tmpy - 1) * 8 + (tmpx - 1)] != 0:
                        path_ok = false
        if path_ok == true and path_done == false:
            tmpx = p_fx + stepx * 3
            tmpy = p_fy + stepy * 3
            if tmpx == p_tx and tmpy == p_ty:
                path_done = true
            else:
                if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                    path_ok = false
                else:
                    if board[(tmpy - 1) * 8 + (tmpx - 1)] != 0:
                        path_ok = false
        if path_ok == true and path_done == false:
            tmpx = p_fx + stepx * 4
            tmpy = p_fy + stepy * 4
            if tmpx == p_tx and tmpy == p_ty:
                path_done = true
            else:
                if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                    path_ok = false
                else:
                    if board[(tmpy - 1) * 8 + (tmpx - 1)] != 0:
                        path_ok = false
        if path_ok == true and path_done == false:
            tmpx = p_fx + stepx * 5
            tmpy = p_fy + stepy * 5
            if tmpx == p_tx and tmpy == p_ty:
                path_done = true
            else:
                if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                    path_ok = false
                else:
                    if board[(tmpy - 1) * 8 + (tmpx - 1)] != 0:
                        path_ok = false
        if path_ok == true and path_done == false:
            tmpx = p_fx + stepx * 6
            tmpy = p_fy + stepy * 6
            if tmpx == p_tx and tmpy == p_ty:
                path_done = true
            else:
                if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                    path_ok = false
                else:
                    if board[(tmpy - 1) * 8 + (tmpx - 1)] != 0:
                        path_ok = false

    function attack_match(p_x, p_y, p_code):
        if check_found == false:
            if p_x >= 1 and p_x <= 8 and p_y >= 1 and p_y <= 8:
                if board[(p_y - 1) * 8 + (p_x - 1)] == p_code:
                    check_found = true

    function ray_scan(p_x, p_y, p_dx, p_dy, p_colour, p_kind):
        ray_blocked = false
        if ray_blocked == false and check_found == false:
            tmpx = p_x + p_dx * 1
            tmpy = p_y + p_dy * 1
            if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                ray_blocked = true
            else:
                target = board[(tmpy - 1) * 8 + (tmpx - 1)]
                if target != 0:
                    ray_blocked = true
                    if p_colour == 1:
                        if p_kind == 1:
                            if target == 4 or target == 5:
                                check_found = true
                        else:
                            if target == 3 or target == 5:
                                check_found = true
                    else:
                        if p_kind == 1:
                            if target == 0 - 4 or target == 0 - 5:
                                check_found = true
                        else:
                            if target == 0 - 3 or target == 0 - 5:
                                check_found = true
        if ray_blocked == false and check_found == false:
            tmpx = p_x + p_dx * 2
            tmpy = p_y + p_dy * 2
            if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                ray_blocked = true
            else:
                target = board[(tmpy - 1) * 8 + (tmpx - 1)]
                if target != 0:
                    ray_blocked = true
                    if p_colour == 1:
                        if p_kind == 1:
                            if target == 4 or target == 5:
                                check_found = true
                        else:
                            if target == 3 or target == 5:
                                check_found = true
                    else:
                        if p_kind == 1:
                            if target == 0 - 4 or target == 0 - 5:
                                check_found = true
                        else:
                            if target == 0 - 3 or target == 0 - 5:
                                check_found = true
        if ray_blocked == false and check_found == false:
            tmpx = p_x + p_dx * 3
            tmpy = p_y + p_dy * 3
            if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                ray_blocked = true
            else:
                target = board[(tmpy - 1) * 8 + (tmpx - 1)]
                if target != 0:
                    ray_blocked = true
                    if p_colour == 1:
                        if p_kind == 1:
                            if target == 4 or target == 5:
                                check_found = true
                        else:
                            if target == 3 or target == 5:
                                check_found = true
                    else:
                        if p_kind == 1:
                            if target == 0 - 4 or target == 0 - 5:
                                check_found = true
                        else:
                            if target == 0 - 3 or target == 0 - 5:
                                check_found = true
        if ray_blocked == false and check_found == false:
            tmpx = p_x + p_dx * 4
            tmpy = p_y + p_dy * 4
            if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                ray_blocked = true
            else:
                target = board[(tmpy - 1) * 8 + (tmpx - 1)]
                if target != 0:
                    ray_blocked = true
                    if p_colour == 1:
                        if p_kind == 1:
                            if target == 4 or target == 5:
                                check_found = true
                        else:
                            if target == 3 or target == 5:
                                check_found = true
                    else:
                        if p_kind == 1:
                            if target == 0 - 4 or target == 0 - 5:
                                check_found = true
                        else:
                            if target == 0 - 3 or target == 0 - 5:
                                check_found = true
        if ray_blocked == false and check_found == false:
            tmpx = p_x + p_dx * 5
            tmpy = p_y + p_dy * 5
            if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                ray_blocked = true
            else:
                target = board[(tmpy - 1) * 8 + (tmpx - 1)]
                if target != 0:
                    ray_blocked = true
                    if p_colour == 1:
                        if p_kind == 1:
                            if target == 4 or target == 5:
                                check_found = true
                        else:
                            if target == 3 or target == 5:
                                check_found = true
                    else:
                        if p_kind == 1:
                            if target == 0 - 4 or target == 0 - 5:
                                check_found = true
                        else:
                            if target == 0 - 3 or target == 0 - 5:
                                check_found = true
        if ray_blocked == false and check_found == false:
            tmpx = p_x + p_dx * 6
            tmpy = p_y + p_dy * 6
            if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                ray_blocked = true
            else:
                target = board[(tmpy - 1) * 8 + (tmpx - 1)]
                if target != 0:
                    ray_blocked = true
                    if p_colour == 1:
                        if p_kind == 1:
                            if target == 4 or target == 5:
                                check_found = true
                        else:
                            if target == 3 or target == 5:
                                check_found = true
                    else:
                        if p_kind == 1:
                            if target == 0 - 4 or target == 0 - 5:
                                check_found = true
                        else:
                            if target == 0 - 3 or target == 0 - 5:
                                check_found = true
        if ray_blocked == false and check_found == false:
            tmpx = p_x + p_dx * 7
            tmpy = p_y + p_dy * 7
            if tmpx < 1 or tmpx > 8 or tmpy < 1 or tmpy > 8:
                ray_blocked = true
            else:
                target = board[(tmpy - 1) * 8 + (tmpx - 1)]
                if target != 0:
                    ray_blocked = true
                    if p_colour == 1:
                        if p_kind == 1:
                            if target == 4 or target == 5:
                                check_found = true
                        else:
                            if target == 3 or target == 5:
                                check_found = true
                    else:
                        if p_kind == 1:
                            if target == 0 - 4 or target == 0 - 5:
                                check_found = true
                        else:
                            if target == 0 - 3 or target == 0 - 5:
                                check_found = true

    function square_attacked(p_square, p_colour):
        check_found = false
        fx = (p_square % 8) + 1
        fy = (p_square / 8) + 1
        if p_colour == 1:
            call attack_match(fx - 1, fy - 1, 1)
            call attack_match(fx + 1, fy - 1, 1)
        else:
            call attack_match(fx - 1, fy + 1, 0 - 1)
            call attack_match(fx + 1, fy + 1, 0 - 1)
        if p_colour == 1:
            call attack_match(fx + (1), fy + (2), 2)
            call attack_match(fx + (2), fy + (1), 2)
            call attack_match(fx + (2), fy + (-1), 2)
            call attack_match(fx + (1), fy + (-2), 2)
            call attack_match(fx + (-1), fy + (-2), 2)
            call attack_match(fx + (-2), fy + (-1), 2)
            call attack_match(fx + (-2), fy + (1), 2)
            call attack_match(fx + (-1), fy + (2), 2)
        else:
            call attack_match(fx + (1), fy + (2), 0 - 2)
            call attack_match(fx + (2), fy + (1), 0 - 2)
            call attack_match(fx + (2), fy + (-1), 0 - 2)
            call attack_match(fx + (1), fy + (-2), 0 - 2)
            call attack_match(fx + (-1), fy + (-2), 0 - 2)
            call attack_match(fx + (-2), fy + (-1), 0 - 2)
            call attack_match(fx + (-2), fy + (1), 0 - 2)
            call attack_match(fx + (-1), fy + (2), 0 - 2)
        if p_colour == 1:
            call attack_match(fx + (1), fy + (1), 6)
            call attack_match(fx + (1), fy + (0), 6)
            call attack_match(fx + (1), fy + (-1), 6)
            call attack_match(fx + (0), fy + (1), 6)
            call attack_match(fx + (0), fy + (-1), 6)
            call attack_match(fx + (-1), fy + (1), 6)
            call attack_match(fx + (-1), fy + (0), 6)
            call attack_match(fx + (-1), fy + (-1), 6)
        else:
            call attack_match(fx + (1), fy + (1), 0 - 6)
            call attack_match(fx + (1), fy + (0), 0 - 6)
            call attack_match(fx + (1), fy + (-1), 0 - 6)
            call attack_match(fx + (0), fy + (1), 0 - 6)
            call attack_match(fx + (0), fy + (-1), 0 - 6)
            call attack_match(fx + (-1), fy + (1), 0 - 6)
            call attack_match(fx + (-1), fy + (0), 0 - 6)
            call attack_match(fx + (-1), fy + (-1), 0 - 6)
        call ray_scan(fx, fy, 1, 0, p_colour, 1)
        call ray_scan(fx, fy, -1, 0, p_colour, 1)
        call ray_scan(fx, fy, 0, 1, p_colour, 1)
        call ray_scan(fx, fy, 0, -1, p_colour, 1)
        call ray_scan(fx, fy, 1, 1, p_colour, 2)
        call ray_scan(fx, fy, 1, -1, p_colour, 2)
        call ray_scan(fx, fy, -1, 1, p_colour, 2)
        call ray_scan(fx, fy, -1, -1, p_colour, 2)

    function validate_move(p_from, p_to, p_promo, p_colour):
        legal = false
        pseudo = false
        save_ep_idx = -1
        save_rook_from = -1
        save_rook_to = -1
        promotion_piece = 0
        if p_from >= 0 and p_from < 64 and p_to >= 0 and p_to < 64 and p_from != p_to:
            piece = board[p_from]
            target = board[p_to]
            piece_type = piece
            if piece_type < 0:
                piece_type = 0 - piece_type
            fx = (p_from % 8) + 1
            fy = (p_from / 8) + 1
            tx = (p_to % 8) + 1
            ty = (p_to / 8) + 1
            dx = tx - fx
            dy = ty - fy
            if dx < 0:
                dx = 0 - dx
            if dy < 0:
                dy = 0 - dy
            if (p_colour == 1 and piece > 0) or (p_colour == 2 and piece < 0):
                if not ((p_colour == 1 and target > 0) or (p_colour == 2 and target < 0)):
                    if target != 6 and target != 0 - 6:
                        if piece == 1:
                            if tx == fx and ty == fy + 1 and target == 0:
                                pseudo = true
                            if tx == fx and fy == 2 and ty == 4 and target == 0 and board[16 + (fx - 1)] == 0:
                                pseudo = true
                            if dx == 1 and ty == fy + 1 and target < 0:
                                pseudo = true
                            if dx == 1 and ty == fy + 1 and target == 0 and p_to == ep_square:
                                if board[(fy - 1) * 8 + (tx - 1)] == 0 - 1:
                                    pseudo = true
                        if piece == 0 - 1:
                            if tx == fx and ty == fy - 1 and target == 0:
                                pseudo = true
                            if tx == fx and fy == 7 and ty == 5 and target == 0 and board[40 + (fx - 1)] == 0:
                                pseudo = true
                            if dx == 1 and ty == fy - 1 and target > 0:
                                pseudo = true
                            if dx == 1 and ty == fy - 1 and target == 0 and p_to == ep_square:
                                if board[(fy - 1) * 8 + (tx - 1)] == 1:
                                    pseudo = true
                        if piece_type == 2:
                            if (dx == 1 and dy == 2) or (dx == 2 and dy == 1):
                                pseudo = true
                        if piece_type == 3:
                            if dx == dy and dx > 0:
                                call path_clear(fx, fy, tx, ty)
                                if path_ok == true:
                                    pseudo = true
                        if piece_type == 4:
                            if (dx == 0 and dy > 0) or (dy == 0 and dx > 0):
                                call path_clear(fx, fy, tx, ty)
                                if path_ok == true:
                                    pseudo = true
                        if piece_type == 5:
                            if (dx == dy and dx > 0) or (dx == 0 and dy > 0) or (dy == 0 and dx > 0):
                                call path_clear(fx, fy, tx, ty)
                                if path_ok == true:
                                    pseudo = true
                        if piece_type == 6:
                            if dx <= 1 and dy <= 1:
                                pseudo = true
                            if p_colour == 1 and p_from == 4 and p_to == 6 and white_king_moved == false and white_rook_h_moved == false and board[5] == 0 and board[6] == 0 and board[7] == 4:
                                call square_attacked(4, 2)
                                if check_found == false:
                                    save_mid_piece = board[5]
                                    board[4] = 0
                                    board[5] = 6
                                    call square_attacked(5, 2)
                                    board[4] = 6
                                    board[5] = save_mid_piece
                                    if check_found == false:
                                        pseudo = true
                                        save_rook_from = 7
                                        save_rook_to = 5
                            if p_colour == 1 and p_from == 4 and p_to == 2 and white_king_moved == false and white_rook_a_moved == false and board[1] == 0 and board[2] == 0 and board[3] == 0 and board[0] == 4:
                                call square_attacked(4, 2)
                                if check_found == false:
                                    save_mid_piece = board[3]
                                    board[4] = 0
                                    board[3] = 6
                                    call square_attacked(3, 2)
                                    board[4] = 6
                                    board[3] = save_mid_piece
                                    if check_found == false:
                                        pseudo = true
                                        save_rook_from = 0
                                        save_rook_to = 3
                            if p_colour == 2 and p_from == 60 and p_to == 62 and black_king_moved == false and black_rook_h_moved == false and board[61] == 0 and board[62] == 0 and board[63] == 0 - 4:
                                call square_attacked(60, 1)
                                if check_found == false:
                                    save_mid_piece = board[61]
                                    board[60] = 0
                                    board[61] = 0 - 6
                                    call square_attacked(61, 1)
                                    board[60] = 0 - 6
                                    board[61] = save_mid_piece
                                    if check_found == false:
                                        pseudo = true
                                        save_rook_from = 63
                                        save_rook_to = 61
                            if p_colour == 2 and p_from == 60 and p_to == 58 and black_king_moved == false and black_rook_a_moved == false and board[57] == 0 and board[58] == 0 and board[59] == 0 and board[56] == 0 - 4:
                                call square_attacked(60, 1)
                                if check_found == false:
                                    save_mid_piece = board[59]
                                    board[60] = 0
                                    board[59] = 0 - 6
                                    call square_attacked(59, 1)
                                    board[60] = 0 - 6
                                    board[59] = save_mid_piece
                                    if check_found == false:
                                        pseudo = true
                                        save_rook_from = 56
                                        save_rook_to = 59
                        if pseudo == true:
                            if piece_type == 1 and (ty == 1 or ty == 8):
                                if p_promo >= 2 and p_promo <= 5:
                                    if piece > 0:
                                        promotion_piece = p_promo
                                    else:
                                        promotion_piece = 0 - p_promo
                                else:
                                    pseudo = false
                            else:
                                if p_promo != 0:
                                    pseudo = false
                        if pseudo == true:
                            save_from_piece = board[p_from]
                            save_to_piece = board[p_to]
                            save_ep_idx = -1
                            save_ep_piece = 0
                            save_rook_from_piece = 0
                            save_rook_to_piece = 0
                            fx = (p_from % 8) + 1
                            fy = (p_from / 8) + 1
                            tx = (p_to % 8) + 1
                            if piece_type == 1 and p_to == ep_square and save_to_piece == 0 and fx != tx:
                                save_ep_idx = (fy - 1) * 8 + (tx - 1)
                                save_ep_piece = board[save_ep_idx]
                                board[save_ep_idx] = 0
                            board[p_from] = 0
                            if promotion_piece != 0:
                                board[p_to] = promotion_piece
                            else:
                                board[p_to] = save_from_piece
                            if save_rook_from >= 0:
                                save_rook_from_piece = board[save_rook_from]
                                save_rook_to_piece = board[save_rook_to]
                                board[save_rook_to] = board[save_rook_from]
                                board[save_rook_from] = 0
                            if p_colour == 1:
                                king_square = white_king_square
                                if save_from_piece == 6:
                                    king_square = p_to
                                call square_attacked(king_square, 2)
                            else:
                                king_square = black_king_square
                                if save_from_piece == 0 - 6:
                                    king_square = p_to
                                call square_attacked(king_square, 1)
                            if check_found == false:
                                legal = true
                            board[p_from] = save_from_piece
                            board[p_to] = save_to_piece
                            if save_ep_idx >= 0:
                                board[save_ep_idx] = save_ep_piece
                            if save_rook_from >= 0:
                                board[save_rook_from] = save_rook_from_piece
                                board[save_rook_to] = save_rook_to_piece

    function compute_hash():
        position_hash = 17
        position_hash = (position_hash * 31 + board[0] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[1] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[2] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[3] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[4] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[5] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[6] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[7] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[8] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[9] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[10] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[11] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[12] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[13] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[14] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[15] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[16] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[17] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[18] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[19] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[20] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[21] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[22] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[23] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[24] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[25] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[26] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[27] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[28] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[29] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[30] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[31] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[32] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[33] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[34] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[35] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[36] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[37] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[38] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[39] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[40] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[41] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[42] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[43] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[44] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[45] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[46] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[47] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[48] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[49] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[50] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[51] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[52] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[53] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[54] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[55] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[56] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[57] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[58] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[59] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[60] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[61] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[62] + 7) % 2147483647
        position_hash = (position_hash * 31 + board[63] + 7) % 2147483647
        position_hash = (position_hash * 31 + turn_colour) % 2147483647
        position_hash = (position_hash * 31 + ep_square + 2) % 2147483647
        if white_king_moved == true:
            position_hash = (position_hash * 31 + 1) % 2147483647
        else:
            position_hash = (position_hash * 31) % 2147483647
        if black_king_moved == true:
            position_hash = (position_hash * 31 + 1) % 2147483647
        else:
            position_hash = (position_hash * 31) % 2147483647
        if white_rook_a_moved == true:
            position_hash = (position_hash * 31 + 1) % 2147483647
        else:
            position_hash = (position_hash * 31) % 2147483647
        if white_rook_h_moved == true:
            position_hash = (position_hash * 31 + 1) % 2147483647
        else:
            position_hash = (position_hash * 31) % 2147483647
        if black_rook_a_moved == true:
            position_hash = (position_hash * 31 + 1) % 2147483647
        else:
            position_hash = (position_hash * 31) % 2147483647
        if black_rook_h_moved == true:
            position_hash = (position_hash * 31 + 1) % 2147483647
        else:
            position_hash = (position_hash * 31) % 2147483647

    function count_repetition_a():
        if history_count > 0 and history[0] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 1 and history[1] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 2 and history[2] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 3 and history[3] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 4 and history[4] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 5 and history[5] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 6 and history[6] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 7 and history[7] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 8 and history[8] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 9 and history[9] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 10 and history[10] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 11 and history[11] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 12 and history[12] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 13 and history[13] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 14 and history[14] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 15 and history[15] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 16 and history[16] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 17 and history[17] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 18 and history[18] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 19 and history[19] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 20 and history[20] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 21 and history[21] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 22 and history[22] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 23 and history[23] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 24 and history[24] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 25 and history[25] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 26 and history[26] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 27 and history[27] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 28 and history[28] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 29 and history[29] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 30 and history[30] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 31 and history[31] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 32 and history[32] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 33 and history[33] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 34 and history[34] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 35 and history[35] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 36 and history[36] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 37 and history[37] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 38 and history[38] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 39 and history[39] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 40 and history[40] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 41 and history[41] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 42 and history[42] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 43 and history[43] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 44 and history[44] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 45 and history[45] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 46 and history[46] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 47 and history[47] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 48 and history[48] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 49 and history[49] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 50 and history[50] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 51 and history[51] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 52 and history[52] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 53 and history[53] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 54 and history[54] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 55 and history[55] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 56 and history[56] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 57 and history[57] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 58 and history[58] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 59 and history[59] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 60 and history[60] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 61 and history[61] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 62 and history[62] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 63 and history[63] == position_hash:
            repetition_count = repetition_count + 1

    function count_repetition_b():
        if history_count > 64 and history[64] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 65 and history[65] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 66 and history[66] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 67 and history[67] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 68 and history[68] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 69 and history[69] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 70 and history[70] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 71 and history[71] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 72 and history[72] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 73 and history[73] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 74 and history[74] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 75 and history[75] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 76 and history[76] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 77 and history[77] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 78 and history[78] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 79 and history[79] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 80 and history[80] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 81 and history[81] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 82 and history[82] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 83 and history[83] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 84 and history[84] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 85 and history[85] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 86 and history[86] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 87 and history[87] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 88 and history[88] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 89 and history[89] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 90 and history[90] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 91 and history[91] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 92 and history[92] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 93 and history[93] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 94 and history[94] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 95 and history[95] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 96 and history[96] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 97 and history[97] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 98 and history[98] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 99 and history[99] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 100 and history[100] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 101 and history[101] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 102 and history[102] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 103 and history[103] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 104 and history[104] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 105 and history[105] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 106 and history[106] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 107 and history[107] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 108 and history[108] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 109 and history[109] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 110 and history[110] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 111 and history[111] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 112 and history[112] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 113 and history[113] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 114 and history[114] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 115 and history[115] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 116 and history[116] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 117 and history[117] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 118 and history[118] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 119 and history[119] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 120 and history[120] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 121 and history[121] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 122 and history[122] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 123 and history[123] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 124 and history[124] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 125 and history[125] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 126 and history[126] == position_hash:
            repetition_count = repetition_count + 1
        if history_count > 127 and history[127] == position_hash:
            repetition_count = repetition_count + 1

    function record_position():
        call compute_hash()
        repetition_count = 0
        call count_repetition_a()
        call count_repetition_b()
        history[history_count % 128] = position_hash
        history_count = history_count + 1
        repetition_count = repetition_count + 1

    function scan_material(p_i):
        piece = board[p_i]
        piece_type = piece
        if piece_type < 0:
            piece_type = 0 - piece_type
        if piece_type == 1 or piece_type == 4 or piece_type == 5:
            material_major = true
        if piece_type == 2:
            knight_count = knight_count + 1
        if piece_type == 3:
            bishop_count = bishop_count + 1
            if ((p_i % 8) + (p_i / 8)) % 2 == 0:
                bishop_dark = bishop_dark + 1
            else:
                bishop_light = bishop_light + 1

    function check_insufficient():
        material_major = false
        bishop_count = 0
        knight_count = 0
        bishop_light = 0
        bishop_dark = 0
        call scan_material(0)
        call scan_material(1)
        call scan_material(2)
        call scan_material(3)
        call scan_material(4)
        call scan_material(5)
        call scan_material(6)
        call scan_material(7)
        call scan_material(8)
        call scan_material(9)
        call scan_material(10)
        call scan_material(11)
        call scan_material(12)
        call scan_material(13)
        call scan_material(14)
        call scan_material(15)
        call scan_material(16)
        call scan_material(17)
        call scan_material(18)
        call scan_material(19)
        call scan_material(20)
        call scan_material(21)
        call scan_material(22)
        call scan_material(23)
        call scan_material(24)
        call scan_material(25)
        call scan_material(26)
        call scan_material(27)
        call scan_material(28)
        call scan_material(29)
        call scan_material(30)
        call scan_material(31)
        call scan_material(32)
        call scan_material(33)
        call scan_material(34)
        call scan_material(35)
        call scan_material(36)
        call scan_material(37)
        call scan_material(38)
        call scan_material(39)
        call scan_material(40)
        call scan_material(41)
        call scan_material(42)
        call scan_material(43)
        call scan_material(44)
        call scan_material(45)
        call scan_material(46)
        call scan_material(47)
        call scan_material(48)
        call scan_material(49)
        call scan_material(50)
        call scan_material(51)
        call scan_material(52)
        call scan_material(53)
        call scan_material(54)
        call scan_material(55)
        call scan_material(56)
        call scan_material(57)
        call scan_material(58)
        call scan_material(59)
        call scan_material(60)
        call scan_material(61)
        call scan_material(62)
        call scan_material(63)
        insufficient = false
        if material_major == false:
            if bishop_count == 0 and knight_count == 0:
                insufficient = true
            if bishop_count == 0 and knight_count <= 2:
                insufficient = true
            if knight_count == 0 and bishop_count > 0 and (bishop_light == 0 or bishop_dark == 0):
                insufficient = true

    function try_candidate_xy(p_from, p_x, p_y, p_colour):
        if any_legal == false and p_x >= 1 and p_x <= 8 and p_y >= 1 and p_y <= 8:
            candidate_promo = 0
            piece = board[p_from]
            piece_type = piece
            if piece_type < 0:
                piece_type = 0 - piece_type
            if piece_type == 1 and (p_y == 1 or p_y == 8):
                candidate_promo = 5
            call validate_move(p_from, (p_y - 1) * 8 + (p_x - 1), candidate_promo, p_colour)
            if legal == true:
                any_legal = true

    function try_moves_from(p_from, p_colour):
        piece = board[p_from]
        piece_type = piece
        fx = (p_from % 8) + 1
        fy = (p_from / 8) + 1
        if piece_type < 0:
            piece_type = 0 - piece_type
        if (p_colour == 1 and piece > 0) or (p_colour == 2 and piece < 0):
            if piece_type == 1:
                if p_colour == 1:
                    call try_candidate_xy(p_from, fx, fy + 1, p_colour)
                    call try_candidate_xy(p_from, fx, fy + 2, p_colour)
                    call try_candidate_xy(p_from, fx - 1, fy + 1, p_colour)
                    call try_candidate_xy(p_from, fx + 1, fy + 1, p_colour)
                else:
                    call try_candidate_xy(p_from, fx, fy - 1, p_colour)
                    call try_candidate_xy(p_from, fx, fy - 2, p_colour)
                    call try_candidate_xy(p_from, fx - 1, fy - 1, p_colour)
                    call try_candidate_xy(p_from, fx + 1, fy - 1, p_colour)
            if piece_type == 2:
                call try_candidate_xy(p_from, fx + (1), fy + (2), p_colour)
                call try_candidate_xy(p_from, fx + (2), fy + (1), p_colour)
                call try_candidate_xy(p_from, fx + (2), fy + (-1), p_colour)
                call try_candidate_xy(p_from, fx + (1), fy + (-2), p_colour)
                call try_candidate_xy(p_from, fx + (-1), fy + (-2), p_colour)
                call try_candidate_xy(p_from, fx + (-2), fy + (-1), p_colour)
                call try_candidate_xy(p_from, fx + (-2), fy + (1), p_colour)
                call try_candidate_xy(p_from, fx + (-1), fy + (2), p_colour)
            if piece_type == 3 or piece_type == 5:
                call try_candidate_xy(p_from, fx + (1) * 1, fy + (1) * 1, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 2, fy + (1) * 2, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 3, fy + (1) * 3, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 4, fy + (1) * 4, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 5, fy + (1) * 5, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 6, fy + (1) * 6, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 7, fy + (1) * 7, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 1, fy + (-1) * 1, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 2, fy + (-1) * 2, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 3, fy + (-1) * 3, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 4, fy + (-1) * 4, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 5, fy + (-1) * 5, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 6, fy + (-1) * 6, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 7, fy + (-1) * 7, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 1, fy + (1) * 1, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 2, fy + (1) * 2, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 3, fy + (1) * 3, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 4, fy + (1) * 4, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 5, fy + (1) * 5, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 6, fy + (1) * 6, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 7, fy + (1) * 7, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 1, fy + (-1) * 1, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 2, fy + (-1) * 2, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 3, fy + (-1) * 3, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 4, fy + (-1) * 4, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 5, fy + (-1) * 5, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 6, fy + (-1) * 6, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 7, fy + (-1) * 7, p_colour)
            if piece_type == 4 or piece_type == 5:
                call try_candidate_xy(p_from, fx + (1) * 1, fy + (0) * 1, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 2, fy + (0) * 2, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 3, fy + (0) * 3, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 4, fy + (0) * 4, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 5, fy + (0) * 5, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 6, fy + (0) * 6, p_colour)
                call try_candidate_xy(p_from, fx + (1) * 7, fy + (0) * 7, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 1, fy + (0) * 1, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 2, fy + (0) * 2, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 3, fy + (0) * 3, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 4, fy + (0) * 4, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 5, fy + (0) * 5, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 6, fy + (0) * 6, p_colour)
                call try_candidate_xy(p_from, fx + (-1) * 7, fy + (0) * 7, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 1, fy + (1) * 1, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 2, fy + (1) * 2, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 3, fy + (1) * 3, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 4, fy + (1) * 4, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 5, fy + (1) * 5, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 6, fy + (1) * 6, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 7, fy + (1) * 7, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 1, fy + (-1) * 1, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 2, fy + (-1) * 2, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 3, fy + (-1) * 3, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 4, fy + (-1) * 4, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 5, fy + (-1) * 5, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 6, fy + (-1) * 6, p_colour)
                call try_candidate_xy(p_from, fx + (0) * 7, fy + (-1) * 7, p_colour)
            if piece_type == 6:
                call try_candidate_xy(p_from, fx + (1), fy + (1), p_colour)
                call try_candidate_xy(p_from, fx + (1), fy + (0), p_colour)
                call try_candidate_xy(p_from, fx + (1), fy + (-1), p_colour)
                call try_candidate_xy(p_from, fx + (0), fy + (1), p_colour)
                call try_candidate_xy(p_from, fx + (0), fy + (-1), p_colour)
                call try_candidate_xy(p_from, fx + (-1), fy + (1), p_colour)
                call try_candidate_xy(p_from, fx + (-1), fy + (0), p_colour)
                call try_candidate_xy(p_from, fx + (-1), fy + (-1), p_colour)
                call try_candidate_xy(p_from, fx + 2, fy, p_colour)
                call try_candidate_xy(p_from, fx - 2, fy, p_colour)

    function has_any_legal(p_colour):
        any_legal = false
        if any_legal == false:
            call try_moves_from(0, p_colour)
        if any_legal == false:
            call try_moves_from(1, p_colour)
        if any_legal == false:
            call try_moves_from(2, p_colour)
        if any_legal == false:
            call try_moves_from(3, p_colour)
        if any_legal == false:
            call try_moves_from(4, p_colour)
        if any_legal == false:
            call try_moves_from(5, p_colour)
        if any_legal == false:
            call try_moves_from(6, p_colour)
        if any_legal == false:
            call try_moves_from(7, p_colour)
        if any_legal == false:
            call try_moves_from(8, p_colour)
        if any_legal == false:
            call try_moves_from(9, p_colour)
        if any_legal == false:
            call try_moves_from(10, p_colour)
        if any_legal == false:
            call try_moves_from(11, p_colour)
        if any_legal == false:
            call try_moves_from(12, p_colour)
        if any_legal == false:
            call try_moves_from(13, p_colour)
        if any_legal == false:
            call try_moves_from(14, p_colour)
        if any_legal == false:
            call try_moves_from(15, p_colour)
        if any_legal == false:
            call try_moves_from(16, p_colour)
        if any_legal == false:
            call try_moves_from(17, p_colour)
        if any_legal == false:
            call try_moves_from(18, p_colour)
        if any_legal == false:
            call try_moves_from(19, p_colour)
        if any_legal == false:
            call try_moves_from(20, p_colour)
        if any_legal == false:
            call try_moves_from(21, p_colour)
        if any_legal == false:
            call try_moves_from(22, p_colour)
        if any_legal == false:
            call try_moves_from(23, p_colour)
        if any_legal == false:
            call try_moves_from(24, p_colour)
        if any_legal == false:
            call try_moves_from(25, p_colour)
        if any_legal == false:
            call try_moves_from(26, p_colour)
        if any_legal == false:
            call try_moves_from(27, p_colour)
        if any_legal == false:
            call try_moves_from(28, p_colour)
        if any_legal == false:
            call try_moves_from(29, p_colour)
        if any_legal == false:
            call try_moves_from(30, p_colour)
        if any_legal == false:
            call try_moves_from(31, p_colour)
        if any_legal == false:
            call try_moves_from(32, p_colour)
        if any_legal == false:
            call try_moves_from(33, p_colour)
        if any_legal == false:
            call try_moves_from(34, p_colour)
        if any_legal == false:
            call try_moves_from(35, p_colour)
        if any_legal == false:
            call try_moves_from(36, p_colour)
        if any_legal == false:
            call try_moves_from(37, p_colour)
        if any_legal == false:
            call try_moves_from(38, p_colour)
        if any_legal == false:
            call try_moves_from(39, p_colour)
        if any_legal == false:
            call try_moves_from(40, p_colour)
        if any_legal == false:
            call try_moves_from(41, p_colour)
        if any_legal == false:
            call try_moves_from(42, p_colour)
        if any_legal == false:
            call try_moves_from(43, p_colour)
        if any_legal == false:
            call try_moves_from(44, p_colour)
        if any_legal == false:
            call try_moves_from(45, p_colour)
        if any_legal == false:
            call try_moves_from(46, p_colour)
        if any_legal == false:
            call try_moves_from(47, p_colour)
        if any_legal == false:
            call try_moves_from(48, p_colour)
        if any_legal == false:
            call try_moves_from(49, p_colour)
        if any_legal == false:
            call try_moves_from(50, p_colour)
        if any_legal == false:
            call try_moves_from(51, p_colour)
        if any_legal == false:
            call try_moves_from(52, p_colour)
        if any_legal == false:
            call try_moves_from(53, p_colour)
        if any_legal == false:
            call try_moves_from(54, p_colour)
        if any_legal == false:
            call try_moves_from(55, p_colour)
        if any_legal == false:
            call try_moves_from(56, p_colour)
        if any_legal == false:
            call try_moves_from(57, p_colour)
        if any_legal == false:
            call try_moves_from(58, p_colour)
        if any_legal == false:
            call try_moves_from(59, p_colour)
        if any_legal == false:
            call try_moves_from(60, p_colour)
        if any_legal == false:
            call try_moves_from(61, p_colour)
        if any_legal == false:
            call try_moves_from(62, p_colour)
        if any_legal == false:
            call try_moves_from(63, p_colour)

    function apply_move(p_from, p_to, p_promo):
        piece = board[p_from]
        target = board[p_to]
        piece_type = piece
        if piece_type < 0:
            piece_type = 0 - piece_type
        fx = (p_from % 8) + 1
        fy = (p_from / 8) + 1
        tx = (p_to % 8) + 1
        ty = (p_to / 8) + 1
        save_ep_idx = -1
        save_rook_from = -1
        save_rook_to = -1
        if piece == 6:
            white_king_moved = true
        if piece == 0 - 6:
            black_king_moved = true
        if piece == 6:
            white_king_square = p_to
        if piece == 0 - 6:
            black_king_square = p_to
        if p_from == 0 and piece == 4:
            white_rook_a_moved = true
        if p_from == 7 and piece == 4:
            white_rook_h_moved = true
        if p_from == 56 and piece == 0 - 4:
            black_rook_a_moved = true
        if p_from == 63 and piece == 0 - 4:
            black_rook_h_moved = true
        if p_to == 0 and target == 4:
            white_rook_a_moved = true
        if p_to == 7 and target == 4:
            white_rook_h_moved = true
        if p_to == 56 and target == 0 - 4:
            black_rook_a_moved = true
        if p_to == 63 and target == 0 - 4:
            black_rook_h_moved = true
        if piece_type == 1 and p_to == ep_square and target == 0 and fx != tx:
            save_ep_idx = (fy - 1) * 8 + (tx - 1)
            target = board[save_ep_idx]
            board[save_ep_idx] = 0
        if piece_type == 6 and p_from == 4 and p_to == 6:
            save_rook_from = 7
            save_rook_to = 5
        if piece_type == 6 and p_from == 4 and p_to == 2:
            save_rook_from = 0
            save_rook_to = 3
        if piece_type == 6 and p_from == 60 and p_to == 62:
            save_rook_from = 63
            save_rook_to = 61
        if piece_type == 6 and p_from == 60 and p_to == 58:
            save_rook_from = 56
            save_rook_to = 59
        board[p_from] = 0
        if piece_type == 1 and (ty == 1 or ty == 8):
            if piece > 0:
                board[p_to] = p_promo
            else:
                board[p_to] = 0 - p_promo
        else:
            board[p_to] = piece
        if save_rook_from >= 0:
            board[save_rook_to] = board[save_rook_from]
            board[save_rook_from] = 0
        if piece_type == 1 or target != 0:
            halfmove = 0
        else:
            halfmove = halfmove + 1
        ep_square = -1
        if piece == 1 and fy == 2 and ty == 4:
            ep_square = 16 + (fx - 1)
        if piece == 0 - 1 and fy == 7 and ty == 5:
            ep_square = 40 + (fx - 1)
        last_from = p_from
        last_to = p_to
        selected_from = -1
        pending_to = -1
        promotion_pending = false
        promote_q.visible = false
        promote_r.visible = false
        promote_b.visible = false
        promote_n.visible = false
        if turn_colour == 1:
            turn_colour = 2
        else:
            turn_colour = 1
        call redraw_all()
        call record_position()
        call evaluate_game()

    function evaluate_game():
        if game_active == true:
            call check_insufficient()
            if halfmove >= 100:
                game_active = false
                status.text = "Draw — 50-move rule"
            else:
                if repetition_count >= 3:
                    game_active = false
                    status.text = "Draw — threefold repetition"
                else:
                    if insufficient == true:
                        game_active = false
                        status.text = "Draw — insufficient material"
                    else:
                        if turn_colour == 1:
                            king_square = white_king_square
                            call square_attacked(king_square, 2)
                        else:
                            king_square = black_king_square
                            call square_attacked(king_square, 1)
                        in_check = check_found
                        call has_any_legal(turn_colour)
                        if turn_colour == 1:
                            expected_player = white_player
                        else:
                            expected_player = 3 - white_player
                        if any_legal == false:
                            game_active = false
                            if in_check == true:
                                if expected_player == my_player:
                                    status.text = "Checkmate — you lost"
                                else:
                                    status.text = "Checkmate — you won"
                            else:
                                status.text = "Draw — stalemate"
                        else:
                            if expected_player == my_player:
                                if in_check == true:
                                    status.text = "Your turn — CHECK"
                                else:
                                    status.text = "Your turn"
                            else:
                                if in_check == true:
                                    status.text = "Opponent to move — CHECK"
                                else:
                                    status.text = "Opponent to move"

    function mark_sent():
        old_selected = selected_from
        selected_from = -1
        tap_send = false
        promotion_pending = false
        promote_q.visible = false
        promote_r.visible = false
        promote_b.visible = false
        promote_n.visible = false
        call redraw_square(old_selected)
        call redraw_square(pending_to)
        status.text = "Move proposed; waiting for exact-hash acceptance"

    function square_tapped(p_index):
        tap_send = false
        old_selected = selected_from
        if game_active == false:
            status.text = "Start or accept a game first"
        else:
            if my_colour != turn_colour:
                status.text = "Wait for the other player"
            else:
                if promotion_pending == true:
                    status.text = "Choose a promotion piece"
                else:
                    piece = board[p_index]
                    if selected_from < 0:
                        if (my_colour == 1 and piece > 0) or (my_colour == 2 and piece < 0):
                            selected_from = p_index
                            status.text = "Piece selected — choose destination"
                        else:
                            status.text = "Select one of your pieces"
                    else:
                        if p_index == selected_from:
                            selected_from = -1
                            status.text = "Selection cleared"
                        else:
                            if (my_colour == 1 and piece > 0) or (my_colour == 2 and piece < 0):
                                selected_from = p_index
                                status.text = "Piece reselected — choose destination"
                            else:
                                pending_to = p_index
                                piece = board[selected_from]
                                piece_type = piece
                                if piece_type < 0:
                                    piece_type = 0 - piece_type
                                ty = (p_index / 8) + 1
                                if piece_type == 1 and (ty == 1 or ty == 8):
                                    call validate_move(selected_from, pending_to, 5, my_colour)
                                    if legal == true:
                                        promotion_pending = true
                                        promote_q.visible = true
                                        promote_r.visible = true
                                        promote_b.visible = true
                                        promote_n.visible = true
                                        status.text = "Choose promotion: Q, R, B or N"
                                    else:
                                        status.text = "That move is not legal"
                                else:
                                    call validate_move(selected_from, pending_to, 0, my_colour)
                                    if legal == true:
                                        tap_send = true
                                    else:
                                        status.text = "That move is not legal"
        if old_selected >= 0:
            call redraw_square(old_selected)
        if selected_from >= 0:
            call redraw_square(selected_from)
        if pending_to >= 0 and promotion_pending == true:
            call redraw_square(pending_to)

    text title:
        x = 5%
        y = 0.5%
        width = 90%
        height = 5.5%
        text = "Chess"
        size = 22
        color = "#2D2926"
        bold = true
        align = center

    button sq0:
        x = 5.00%
        y = 58.80%
        width = 11.25%
        height = 7.40%
        text = "♖"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq1:
        x = 16.25%
        y = 58.80%
        width = 11.25%
        height = 7.40%
        text = "♘"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq2:
        x = 27.50%
        y = 58.80%
        width = 11.25%
        height = 7.40%
        text = "♗"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq3:
        x = 38.75%
        y = 58.80%
        width = 11.25%
        height = 7.40%
        text = "♕"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq4:
        x = 50.00%
        y = 58.80%
        width = 11.25%
        height = 7.40%
        text = "♔"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq5:
        x = 61.25%
        y = 58.80%
        width = 11.25%
        height = 7.40%
        text = "♗"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq6:
        x = 72.50%
        y = 58.80%
        width = 11.25%
        height = 7.40%
        text = "♘"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq7:
        x = 83.75%
        y = 58.80%
        width = 11.25%
        height = 7.40%
        text = "♖"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq8:
        x = 5.00%
        y = 51.40%
        width = 11.25%
        height = 7.40%
        text = "♙"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq9:
        x = 16.25%
        y = 51.40%
        width = 11.25%
        height = 7.40%
        text = "♙"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq10:
        x = 27.50%
        y = 51.40%
        width = 11.25%
        height = 7.40%
        text = "♙"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq11:
        x = 38.75%
        y = 51.40%
        width = 11.25%
        height = 7.40%
        text = "♙"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq12:
        x = 50.00%
        y = 51.40%
        width = 11.25%
        height = 7.40%
        text = "♙"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq13:
        x = 61.25%
        y = 51.40%
        width = 11.25%
        height = 7.40%
        text = "♙"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq14:
        x = 72.50%
        y = 51.40%
        width = 11.25%
        height = 7.40%
        text = "♙"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq15:
        x = 83.75%
        y = 51.40%
        width = 11.25%
        height = 7.40%
        text = "♙"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq16:
        x = 5.00%
        y = 44.00%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq17:
        x = 16.25%
        y = 44.00%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq18:
        x = 27.50%
        y = 44.00%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq19:
        x = 38.75%
        y = 44.00%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq20:
        x = 50.00%
        y = 44.00%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq21:
        x = 61.25%
        y = 44.00%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq22:
        x = 72.50%
        y = 44.00%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq23:
        x = 83.75%
        y = 44.00%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq24:
        x = 5.00%
        y = 36.60%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq25:
        x = 16.25%
        y = 36.60%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq26:
        x = 27.50%
        y = 36.60%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq27:
        x = 38.75%
        y = 36.60%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq28:
        x = 50.00%
        y = 36.60%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq29:
        x = 61.25%
        y = 36.60%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq30:
        x = 72.50%
        y = 36.60%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq31:
        x = 83.75%
        y = 36.60%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq32:
        x = 5.00%
        y = 29.20%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq33:
        x = 16.25%
        y = 29.20%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq34:
        x = 27.50%
        y = 29.20%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq35:
        x = 38.75%
        y = 29.20%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq36:
        x = 50.00%
        y = 29.20%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq37:
        x = 61.25%
        y = 29.20%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq38:
        x = 72.50%
        y = 29.20%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq39:
        x = 83.75%
        y = 29.20%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq40:
        x = 5.00%
        y = 21.80%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq41:
        x = 16.25%
        y = 21.80%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq42:
        x = 27.50%
        y = 21.80%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq43:
        x = 38.75%
        y = 21.80%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq44:
        x = 50.00%
        y = 21.80%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq45:
        x = 61.25%
        y = 21.80%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq46:
        x = 72.50%
        y = 21.80%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq47:
        x = 83.75%
        y = 21.80%
        width = 11.25%
        height = 7.40%
        text = "·"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq48:
        x = 5.00%
        y = 14.40%
        width = 11.25%
        height = 7.40%
        text = "♟"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq49:
        x = 16.25%
        y = 14.40%
        width = 11.25%
        height = 7.40%
        text = "♟"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq50:
        x = 27.50%
        y = 14.40%
        width = 11.25%
        height = 7.40%
        text = "♟"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq51:
        x = 38.75%
        y = 14.40%
        width = 11.25%
        height = 7.40%
        text = "♟"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq52:
        x = 50.00%
        y = 14.40%
        width = 11.25%
        height = 7.40%
        text = "♟"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq53:
        x = 61.25%
        y = 14.40%
        width = 11.25%
        height = 7.40%
        text = "♟"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq54:
        x = 72.50%
        y = 14.40%
        width = 11.25%
        height = 7.40%
        text = "♟"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq55:
        x = 83.75%
        y = 14.40%
        width = 11.25%
        height = 7.40%
        text = "♟"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq56:
        x = 5.00%
        y = 7.00%
        width = 11.25%
        height = 7.40%
        text = "♜"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq57:
        x = 16.25%
        y = 7.00%
        width = 11.25%
        height = 7.40%
        text = "♞"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq58:
        x = 27.50%
        y = 7.00%
        width = 11.25%
        height = 7.40%
        text = "♝"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq59:
        x = 38.75%
        y = 7.00%
        width = 11.25%
        height = 7.40%
        text = "♛"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq60:
        x = 50.00%
        y = 7.00%
        width = 11.25%
        height = 7.40%
        text = "♚"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq61:
        x = 61.25%
        y = 7.00%
        width = 11.25%
        height = 7.40%
        text = "♝"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    button sq62:
        x = 72.50%
        y = 7.00%
        width = 11.25%
        height = 7.40%
        text = "♞"
        size = 22
        color = "#1D1B19"
        background = "#F0D9B5"
        align = center

    button sq63:
        x = 83.75%
        y = 7.00%
        width = 11.25%
        height = 7.40%
        text = "♜"
        size = 22
        color = "#1D1B19"
        background = "#B58863"
        align = center

    text player_info:
        x = 5%
        y = 67%
        width = 90%
        height = 4%
        text = "Tap Find player to begin"
        size = 12
        color = "#544B46"
        align = center

    text status:
        x = 5%
        y = 71%
        width = 90%
        height = 5%
        text = "Not connected"
        size = 12
        color = "#6B625C"
        align = center

    button find_player:
        x = 5%
        y = 77%
        width = 28%
        height = 7%
        text = "Find player"
        size = 12
        color = "#FFFFFF"
        background = "#7D3440"
        align = center

    button accept_invite:
        x = 36%
        y = 77%
        width = 28%
        height = 7%
        visible = false
        text = "Accept invite"
        size = 12
        color = "#FFFFFF"
        background = "#4F765A"
        align = center

    button resign_button:
        x = 67%
        y = 77%
        width = 28%
        height = 7%
        text = "I give up"
        size = 12
        color = "#FFFFFF"
        background = "#6A5660"
        align = center

    button promote_q:
        x = 5%
        y = 86%
        width = 20%
        height = 7%
        visible = false
        text = "Queen"
        size = 12
        color = "#FFFFFF"
        background = "#7D3440"
        align = center

    button promote_r:
        x = 28%
        y = 86%
        width = 20%
        height = 7%
        visible = false
        text = "Rook"
        size = 12
        color = "#FFFFFF"
        background = "#7D3440"
        align = center

    button promote_b:
        x = 51%
        y = 86%
        width = 20%
        height = 7%
        visible = false
        text = "Bishop"
        size = 12
        color = "#FFFFFF"
        background = "#7D3440"
        align = center

    button promote_n:
        x = 74%
        y = 86%
        width = 21%
        height = 7%
        visible = false
        text = "Knight"
        size = 12
        color = "#FFFFFF"
        background = "#7D3440"
        align = center

    on tap(sq0):
        call square_tapped(0)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq1):
        call square_tapped(1)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq2):
        call square_tapped(2)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq3):
        call square_tapped(3)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq4):
        call square_tapped(4)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq5):
        call square_tapped(5)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq6):
        call square_tapped(6)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq7):
        call square_tapped(7)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq8):
        call square_tapped(8)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq9):
        call square_tapped(9)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq10):
        call square_tapped(10)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq11):
        call square_tapped(11)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq12):
        call square_tapped(12)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq13):
        call square_tapped(13)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq14):
        call square_tapped(14)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq15):
        call square_tapped(15)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq16):
        call square_tapped(16)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq17):
        call square_tapped(17)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq18):
        call square_tapped(18)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq19):
        call square_tapped(19)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq20):
        call square_tapped(20)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq21):
        call square_tapped(21)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq22):
        call square_tapped(22)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq23):
        call square_tapped(23)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq24):
        call square_tapped(24)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq25):
        call square_tapped(25)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq26):
        call square_tapped(26)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq27):
        call square_tapped(27)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq28):
        call square_tapped(28)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq29):
        call square_tapped(29)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq30):
        call square_tapped(30)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq31):
        call square_tapped(31)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq32):
        call square_tapped(32)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq33):
        call square_tapped(33)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq34):
        call square_tapped(34)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq35):
        call square_tapped(35)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq36):
        call square_tapped(36)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq37):
        call square_tapped(37)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq38):
        call square_tapped(38)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq39):
        call square_tapped(39)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq40):
        call square_tapped(40)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq41):
        call square_tapped(41)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq42):
        call square_tapped(42)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq43):
        call square_tapped(43)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq44):
        call square_tapped(44)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq45):
        call square_tapped(45)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq46):
        call square_tapped(46)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq47):
        call square_tapped(47)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq48):
        call square_tapped(48)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq49):
        call square_tapped(49)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq50):
        call square_tapped(50)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq51):
        call square_tapped(51)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq52):
        call square_tapped(52)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq53):
        call square_tapped(53)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq54):
        call square_tapped(54)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq55):
        call square_tapped(55)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq56):
        call square_tapped(56)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq57):
        call square_tapped(57)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq58):
        call square_tapped(58)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq59):
        call square_tapped(59)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq60):
        call square_tapped(60)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq61):
        call square_tapped(61)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq62):
        call square_tapped(62)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(sq63):
        call square_tapped(63)
        if tap_send == true:
            network.begin_action
            network.send(kind=1, value=selected_from)
            network.send(kind=2, value=pending_to)
            network.end_action
            call mark_sent()

    on tap(find_player):
        if game_active == false and session_ready == false:
            network.invite
            status.text = "Invitation posted; waiting for another viewer"

    on network.invite:
        if game_active == false:
            invite_pending = true
            accept_invite.visible = true
            status.text = "Another viewer wants to play"

    on tap(accept_invite):
        if invite_pending == true and game_active == false:
            network.accept_invite
            invite_pending = false
            accept_invite.visible = false
            status.text = "Accepted; waiting for final acknowledgement"

    on network.invite_accepted:
        status.text = "Opponent accepted; final acknowledgement sent"

    on network.session_ready:
        session_ready = true
        my_player = network.my_player
        game_active = false
        call setup_board()
        player_info.text = "Player slot " + my_player + " — negotiating fair colour toss"
        status.text = "Session ready; committing shared randomness"

    on network.random_ready:
        status.text = "Shared seed verified; waiting for scheduled toss"
        network.roll(2)

    on network.roll(2, toss):
        if toss == 1:
            white_player = network.event_player
        else:
            white_player = 3 - network.event_player
        if my_player == white_player:
            my_colour = 1
            player_info.text = "Player " + my_player + " — WHITE"
        else:
            my_colour = 2
            player_info.text = "Player " + my_player + " — BLACK"
        game_active = true
        turn_colour = 1
        call record_position()
        call evaluate_game()

    on network.action(kind, value):
        incoming_valid = false
        incoming_from = -1
        incoming_to = -1
        incoming_promo = 0
        if network.action_count == 2:
            if network.action.kind[0] == 1 and network.action.kind[1] == 2:
                incoming_from = network.action.value[0]
                incoming_to = network.action.value[1]
        if network.action_count == 3:
            if network.action.kind[0] == 1 and network.action.kind[1] == 2 and network.action.kind[2] == 3:
                incoming_from = network.action.value[0]
                incoming_to = network.action.value[1]
                incoming_promo = network.action.value[2]
        if turn_colour == 1:
            expected_player = white_player
        else:
            expected_player = 3 - white_player
        if game_active == true and network.event_player == expected_player and incoming_from >= 0 and incoming_to >= 0:
            call validate_move(incoming_from, incoming_to, incoming_promo, turn_colour)
            if legal == true:
                incoming_valid = true
        if incoming_valid == true:
            network.accept
        else:
            network.reject

    on network.action_committed(kind, value):
        incoming_valid = false
        incoming_from = -1
        incoming_to = -1
        incoming_promo = 0
        if network.action_count == 2:
            if network.action.kind[0] == 1 and network.action.kind[1] == 2:
                incoming_from = network.action.value[0]
                incoming_to = network.action.value[1]
        if network.action_count == 3:
            if network.action.kind[0] == 1 and network.action.kind[1] == 2 and network.action.kind[2] == 3:
                incoming_from = network.action.value[0]
                incoming_to = network.action.value[1]
                incoming_promo = network.action.value[2]
        if turn_colour == 1:
            expected_player = white_player
        else:
            expected_player = 3 - white_player
        if game_active == true and network.event_player == expected_player and incoming_from >= 0 and incoming_to >= 0:
            call validate_move(incoming_from, incoming_to, incoming_promo, turn_colour)
            if legal == true:
                incoming_valid = true
        if incoming_valid == true:
            call apply_move(incoming_from, incoming_to, incoming_promo)
        else:
            game_active = false
            status.text = "Game stopped — invalid committed move"

    on tap(promote_q):
        if promotion_pending == true and game_active == true and my_colour == turn_colour:
            call validate_move(selected_from, pending_to, 5, my_colour)
            if legal == true:
                network.begin_action
                network.send(kind=1, value=selected_from)
                network.send(kind=2, value=pending_to)
                network.send(kind=3, value=5)
                network.end_action
                call mark_sent()
            else:
                status.text = "Promotion move is not legal"

    on tap(promote_r):
        if promotion_pending == true and game_active == true and my_colour == turn_colour:
            call validate_move(selected_from, pending_to, 4, my_colour)
            if legal == true:
                network.begin_action
                network.send(kind=1, value=selected_from)
                network.send(kind=2, value=pending_to)
                network.send(kind=3, value=4)
                network.end_action
                call mark_sent()
            else:
                status.text = "Promotion move is not legal"

    on tap(promote_b):
        if promotion_pending == true and game_active == true and my_colour == turn_colour:
            call validate_move(selected_from, pending_to, 3, my_colour)
            if legal == true:
                network.begin_action
                network.send(kind=1, value=selected_from)
                network.send(kind=2, value=pending_to)
                network.send(kind=3, value=3)
                network.end_action
                call mark_sent()
            else:
                status.text = "Promotion move is not legal"

    on tap(promote_n):
        if promotion_pending == true and game_active == true and my_colour == turn_colour:
            call validate_move(selected_from, pending_to, 2, my_colour)
            if legal == true:
                network.begin_action
                network.send(kind=1, value=selected_from)
                network.send(kind=2, value=pending_to)
                network.send(kind=3, value=2)
                network.end_action
                call mark_sent()
            else:
                status.text = "Promotion move is not legal"

    on tap(resign_button):
        if game_active == true:
            network.send(resign)
            status.text = "Resignation sent; waiting for acknowledgement"

    on network.input(resign):
        if game_active == true and network.event_player >= 1 and network.event_player <= 2:
            network.accept
        else:
            network.reject

    on network.committed(resign):
        if game_active == true:
            game_active = false
            if network.event_player == my_player:
                status.text = "You resigned"
            else:
                status.text = "Opponent resigned — you won"
"""
        ),
        WidgetTemplate(
            id = "flappy",
            name = "Flappy",
            description = "Standalone Flappy-style layout. Position/physics controls are still needed for full play.",
            readiness = WidgetTemplateReadiness.LanguageScaffold,
            source = """widget "Flappy":
    default_width = 360
    default_height = 520
    warn_on_resize = true
    background = "#BDE9F4"

    box ground:
        x = 0%
        y = 86%
        width = 100%
        height = 14%
        background = "#8BC56A"

    box pipe_top:
        x = 68%
        y = 0%
        width = 13%
        height = 30%
        background = "#4FAE58"

    box pipe_bottom:
        x = 68%
        y = 58%
        width = 13%
        height = 28%
        background = "#4FAE58"

    text bird:
        x = 24%
        y = 44%
        width = 16%
        height = 10%
        text = "●>"
        size = 24
        color = "#E6A62E"
        bold = true
        align = center

    text status:
        x = 10%
        y = 6%
        width = 80%
        height = 12%
        text = "Tap Start"
        size = 20
        color = "#24323A"
        bold = true
        align = center

    button start:
        x = 30%
        y = 72%
        width = 40%
        height = 10%
        text = "Start"
        size = 16
        color = "#FFFFFF"
        background = "#7D3440"
        align = center

    on tap(start):
        status.text = "Physics/state opcodes are the next language pass."
"""
        ),
        WidgetTemplate(
            id = "poll",
            name = "Poll",
            description = "Public-network poll. Recent responses are discoverable for about an hour and mailbox copies let the owner aggregate them later.",
            readiness = WidgetTemplateReadiness.Ready,
            source = """widget "Poll":
    default_width = 380
    default_height = 260
    warn_on_resize = false
    background = "#FCF8F9"
    online = public
    mail_copy = true
    input choice = number(1, 2)

    text question:
        x = 7%
        y = 8%
        width = 86%
        height = 24%
        text = "Which one do you prefer?"
        size = 20
        color = "#252126"
        bold = true
        align = center

    button first:
        x = 10%
        y = 40%
        width = 36%
        height = 20%
        text = "Option A"
        size = 15
        color = "#FFFFFF"
        background = "#7D3440"
        align = center

    button second:
        x = 54%
        y = 40%
        width = 36%
        height = 20%
        text = "Option B"
        size = 15
        color = "#FFFFFF"
        background = "#7D3440"
        align = center

    text status:
        x = 10%
        y = 69%
        width = 80%
        height = 14%
        text = "No vote yet"
        size = 13
        color = "#635B60"
        align = center

    on tap(first):
        network.send(choice=1)
        status.text = "Option A submitted"

    on tap(second):
        network.send(choice=2)
        status.text = "Option B submitted"
"""
        ),
        WidgetTemplate(
            id = "quiz",
            name = "Quick quiz",
            description = "A completely local two-answer quiz demonstrating button events without network access.",
            readiness = WidgetTemplateReadiness.Ready,
            source = """widget "Quick Quiz":
    default_width = 400
    default_height = 270
    warn_on_resize = false
    background = "#F8F7FB"

    text question:
        x = 8%
        y = 8%
        width = 84%
        height = 23%
        text = "Which planet is known as the Red Planet?"
        size = 18
        color = "#25232A"
        bold = true
        align = center

    button mars:
        x = 10%
        y = 39%
        width = 36%
        height = 19%
        text = "Mars"
        size = 15
        color = "#FFFFFF"
        background = "#7D3440"
        align = center

    button venus:
        x = 54%
        y = 39%
        width = 36%
        height = 19%
        text = "Venus"
        size = 15
        color = "#FFFFFF"
        background = "#7D3440"
        align = center

    text answer:
        x = 10%
        y = 68%
        width = 80%
        height = 15%
        text = "Choose an answer"
        size = 14
        color = "#5A555D"
        align = center

    on tap(mars):
        answer.text = "Correct!"

    on tap(venus):
        answer.text = "Try again."
"""
        ),
        WidgetTemplate(
            id = "guestbook",
            name = "Guestbook",
            description = "Public-network text example. Text is inert UTF-8 data (1 KiB max) and never re-enters the widget compiler.",
            readiness = WidgetTemplateReadiness.Ready,
            source = """widget "Guestbook":
    default_width = 420
    default_height = 300
    warn_on_resize = true
    background = "#FFF9F4"
    online = public
    mail_copy = true
    input send = button

    text title:
        x = 7%
        y = 6%
        width = 86%
        height = 16%
        text = "Guestbook"
        size = 24
        color = "#70433B"
        bold = true
        align = center

    textinput message:
        x = 8%
        y = 28%
        width = 84%
        height = 26%
        text = "Write a short message"
        size = 14
        color = "#352F31"
        background = "#FFFFFF"
        align = left

    button sign:
        x = 28%
        y = 62%
        width = 44%
        height = 14%
        text = "Sign guestbook"
        size = 14
        color = "#FFFFFF"
        background = "#7D3440"
        align = center

    text status:
        x = 8%
        y = 80%
        width = 84%
        height = 10%
        text = "Messages are plain text only"
        size = 12
        color = "#6A6160"
        align = center

    on tap(sign):
        network.send(send)
        network.text_from(message)
        status.text = "Message published"
"""
        )
    )

    fun find(id: String): WidgetTemplate? = all.firstOrNull { it.id == id }
}
