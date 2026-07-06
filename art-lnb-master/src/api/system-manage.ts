import { AppRouteRecord } from '@/types/router'

const disabledAdminApi = <T>(name: string): Promise<T> =>
  Promise.reject(new Error(`${name} 当前项目未启用，请使用禹邻优鲜业务管理页面`))

export function fetchGetUserList(_params: Api.SystemManage.UserSearchParams) {
  return disabledAdminApi<Api.SystemManage.UserList>('用户列表')
}

export function fetchGetRoleList(_params: Api.SystemManage.RoleSearchParams) {
  return disabledAdminApi<Api.SystemManage.RoleList>('角色列表')
}

export function fetchGetMenuList() {
  return disabledAdminApi<AppRouteRecord[]>('远程菜单')
}
