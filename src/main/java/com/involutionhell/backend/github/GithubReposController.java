package com.involutionhell.backend.github;

import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.service.UserCenterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * 暴露某用户的 GitHub 公开 repos 列表给前端个人主页。
 *
 * 路径参数 identifier 和 /u/{identifier} 一致：
 * - 纯数字 → 按 github_id 查 user_accounts 拿到真实 GitHub login
 * - 字符串 → 按 username 查
 *
 * 注意：参数 identifier 是本站的 github_id 或 username，不是 GitHub login 本身。
 * 我们需要先 resolve 到 UserAccount 拿 avatarUrl 里的 GitHub login（通过 github_id 反查 GitHub API 也行，
 * 但 UserAccount 里的 displayName 不一定等于 login，这里从 avatarUrl 里提取数字 id → 再拿 API 拉 login 会多一跳）。
 *
 * 目前的简化做法：用 avatarUrl "https://avatars.githubusercontent.com/u/{id}?v=4" 里的 id 作为 GitHub id，
 * 直接调 /user/{id} 拿 login。先存缓存映射，重复请求走 Caffeine。
 *
 * 实际上更简单：GithubReposService 按 login 查，我们这里需要 login → 走 /user/{id} 换一次。
 * 但为了避免多一跳，UserAccount 应该把 GitHub login 存下来；这一步是未来优化。
 *
 * 当前实现：从 user_accounts.username ("github_{id}") 提 id → GithubReposService 内部再解析。
 * 更合理的是让 service 支持 byId + byLogin 两个方法；为了简单先只支持 byId。
 */
@RestController
@RequestMapping("/api/user-center/github")
public class GithubReposController {

    private final GithubReposService githubReposService;
    private final UserCenterService userCenterService;

    public GithubReposController(
            GithubReposService githubReposService,
            UserCenterService userCenterService
    ) {
        this.githubReposService = githubReposService;
        this.userCenterService = userCenterService;
    }

    /**
     * 返回指定用户公开 repos 的 top 8。匿名可访问。
     * 注意：GitHub API 需要 login 字符串，不直接支持 id 路由；这里用 github_id 换 login。
     */
    @GetMapping("/repos/{identifier}")
    public ApiResponse<List<GithubRepoDto>> listRepos(@PathVariable String identifier) {
        Optional<UserAccount> account = userCenterService.findByIdentifier(identifier);
        if (account.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        Long githubId = account.get().githubId();
        if (githubId == null) {
            return ApiResponse.ok(List.of());
        }
        // 直接走 github id → login 查询（GitHub API 支持）
        return ApiResponse.ok(githubReposService.listByGithubId(githubId));
    }
}
