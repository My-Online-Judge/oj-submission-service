package vn.thanhtuanle.stats;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vn.thanhtuanle.common.constant.Routes;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.stats.dto.UserStatsDto;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class UserStatsController {

    private final UserStatsService stats;

    @GetMapping(Routes.USER_STATS)
    @Operation(summary = "A user's public statistics (public; slugs of inactive problems are shown to the owner only)")
    public ApiResponse<UserStatsDto> stats(@PathVariable UUID userId) {
        return ApiResponse.success(stats.stats(userId, viewer()));
    }

    /** The caller's id, or null when anonymous. A public endpoint never calls CurrentUser, which throws then. */
    private static UUID viewer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            try {
                return UUID.fromString(jwt.getToken().getClaimAsString(OjJwtDecoders.UID_CLAIM));
            } catch (IllegalArgumentException | NullPointerException e) {
                return null;
            }
        }
        return null;
    }
}
