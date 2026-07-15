import request from '@/utils/http';
export function fetchLogin(params: Api.Auth.LoginParams) {
    return request.post<Api.Auth.LoginResponse>({
        url: '/api/admin/auth/login',
        params
    });
}
export function fetchGetUserInfo() {
    return request
        .get<{
        name: string;
        role: string;
    }>({
        url: '/api/admin/auth/profile'
    })
        .then((profile) => ({
        buttons: [],
        roles: profile.role === 'SUPER' ? ['R_SUPER'] : ['R_ADMIN'],
        userId: 1,
        userName: profile.name,
        email: '',
        avatar: ''
    }));
}
export function fetchLogout() {
    return request.post<void>({
        url: '/api/admin/auth/logout',
        showErrorMessage: false
    });
}
