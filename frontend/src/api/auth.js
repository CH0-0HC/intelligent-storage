import request from '@/utils/request'

export function login(data) {
  return request({
    url: '/auth/login',
    method: 'post',
    data
  })
}

export function register(data) {
  return request({
    url: '/auth/register',
    method: 'post',
    data
  })
}

export function logout() {
  return request({
    url: '/auth/logout',
    method: 'post'
  })
}

export function getCurrentUser() {
  return request({
    url: '/auth/currentUser',
    method: 'get'
  })
}

export function updatePassword(data) {
  return request({
    url: '/auth/updatePassword',
    method: 'post',
    data
  })
}

/**
 * 自助修改个人资料（昵称 / 邮箱 / 手机号）。
 *
 * 【为什么不用 api/user.js 的 updateUser】
 * updateUser 打的是 PUT /user/update，而 UserController 是类级 @SaCheckRole("admin")，
 * 仅管理员可用。个人中心（/user/profile）三个角色都能进，普通用户与商家调它会被 403。
 * 后端已把「改自己的资料」拆到 PUT /auth/profile（id 由服务端从登录态取，不信任请求体），
 * 这里对应改调该接口。管理他人账号仍走 api/user.js 的 updateUser。
 */
export function updateProfile(data) {
  return request({
    url: '/auth/profile',
    method: 'put',
    data
  })
}
