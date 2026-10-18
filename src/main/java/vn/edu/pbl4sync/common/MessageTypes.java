package vn.edu.pbl4sync.common;

public final class MessageTypes {
    private MessageTypes() {}

    public static final String LOGIN_REQUEST = "LOGIN_REQUEST";
    public static final String LOGIN_RESPONSE = "LOGIN_RESPONSE";
    public static final String ERROR = "ERROR";
    public static final String OK = "OK";

    public static final String DASHBOARD_REQUEST = "DASHBOARD_REQUEST";
    public static final String DASHBOARD_RESPONSE = "DASHBOARD_RESPONSE";
    public static final String LIST_WORKSPACES_REQUEST = "LIST_WORKSPACES_REQUEST";
    public static final String LIST_WORKSPACES_RESPONSE = "LIST_WORKSPACES_RESPONSE";
    public static final String LIST_FILES_REQUEST = "LIST_FILES_REQUEST";
    public static final String LIST_FILES_RESPONSE = "LIST_FILES_RESPONSE";
    public static final String LIST_MEMBERS_REQUEST = "LIST_MEMBERS_REQUEST";
    public static final String LIST_MEMBERS_RESPONSE = "LIST_MEMBERS_RESPONSE";
    public static final String LIST_USERS_REQUEST = "LIST_USERS_REQUEST";
    public static final String LIST_USERS_RESPONSE = "LIST_USERS_RESPONSE";
    public static final String LIST_AGENTS_REQUEST = "LIST_AGENTS_REQUEST";
    public static final String LIST_AGENTS_RESPONSE = "LIST_AGENTS_RESPONSE";
    public static final String LIST_ACTIVITY_REQUEST = "LIST_ACTIVITY_REQUEST";
    public static final String LIST_ACTIVITY_RESPONSE = "LIST_ACTIVITY_RESPONSE";

    public static final String CREATE_USER_REQUEST = "CREATE_USER_REQUEST";
    public static final String SET_USER_STATUS_REQUEST = "SET_USER_STATUS_REQUEST";
    public static final String CREATE_WORKSPACE_REQUEST = "CREATE_WORKSPACE_REQUEST";
    public static final String ADD_MEMBER_REQUEST = "ADD_MEMBER_REQUEST";
    public static final String UPDATE_PERMISSION_REQUEST = "UPDATE_PERMISSION_REQUEST";
    public static final String REMOVE_MEMBER_REQUEST = "REMOVE_MEMBER_REQUEST";
    public static final String CHANGE_LEADER_REQUEST = "CHANGE_LEADER_REQUEST";
    public static final String DELETE_WORKSPACE_REQUEST = "DELETE_WORKSPACE_REQUEST";

    public static final String FILE_EVENT = "FILE_EVENT";
    public static final String FILE_DELIVER = "FILE_DELIVER";
    public static final String FILE_DELETE = "FILE_DELETE";
    public static final String FILE_APPLIED = "FILE_APPLIED";
    public static final String FILE_RELAY_UPLOAD = "FILE_RELAY_UPLOAD";
    public static final String FILE_FETCH_REQUEST = "FILE_FETCH_REQUEST";
    public static final String FILE_UPLOAD_REQUEST = "FILE_UPLOAD_REQUEST";
    public static final String FILE_EVENT_RESULT = "FILE_EVENT_RESULT";

    public static final String SYNC_REPORT = "SYNC_REPORT";
    public static final String SYNC_REPORT_RESULT = "SYNC_REPORT_RESULT";
    public static final String SYNC_STATE = "SYNC_STATE";
    public static final String CONFLICT_NOTICE = "CONFLICT_NOTICE";
    public static final String NOTIFICATION = "NOTIFICATION";
    public static final String WORKSPACES_CHANGED = "WORKSPACES_CHANGED";
}
