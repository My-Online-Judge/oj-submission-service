package vn.thanhtuanle.common.constant;

public class Routes {

    private Routes() {
    }

    public static final String BASE_API = "/api/v1";

    public static final String SUBMISSIONS = BASE_API + "/submissions";
    // Only the per-problem rejudge lives here; the gateway sends every other /problems path to problem-service.
    public static final String PROBLEMS = BASE_API + "/problems";
    public static final String LANGUAGES = BASE_API + "/languages";
    public static final String JUDGE_SERVERS = BASE_API + "/judge-servers";

    // Fixed path expected by the QingdaoU judge_server (its BACKEND_URL), outside the /api/v1 group.
    public static final String JUDGE_SERVER_HEARTBEAT = "/api/judge_server_heartbeat";
}
