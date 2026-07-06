import request from '@/utils/http'

/**
 * 登录
 * @param params 登录参数
 * @returns 登录响应
 */
export function fetchLogin(params: Api.Auth.LoginParams) {
  return request.post<Api.Auth.LoginResponse>({
    url: '/api/admin/auth/login',
    params
    // showSuccessMessage: true // 显示成功消息
    // showErrorMessage: false // 不显示错误消息
  })
}

/**
 * 获取用户信息
 * @returns 用户信息
 */
export function fetchGetUserInfo() {
  return request
    .get<{ name: string; role: string }>({
      url: '/api/admin/auth/profile'
    })
    .then((profile) => ({
      buttons: [],
      roles: profile.role === 'SUPER' ? ['R_SUPER'] : ['R_ADMIN'],
      userId: 1,
      userName: profile.name,
      email: '',
      avatar: ''
    }))
}

/**
 * 管理员退出登录
 */
export function fetchLogout() {
  return request.post<void>({
    url: '/api/admin/auth/logout',
    showErrorMessage: false
  })
}
