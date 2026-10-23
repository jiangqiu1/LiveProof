import axios, { AxiosError } from 'axios'
import type { ApiErrorBody, ApiErrorDetail } from '@/types/api'

/**
 * 统一 HTTP 客户端。
 * 走 Vite 代理（/api、/media），前端不直接访问磁盘文件，也不写死后端绝对地址。
 */
export class ApiError extends Error {
  readonly code: string
  readonly status: number | null
  readonly details: ApiErrorDetail[]

  constructor(code: string, message: string, status: number | null = null, details: ApiErrorDetail[] = []) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.details = details
  }
}

export const http = axios.create({
  baseURL: '/',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiErrorBody>) => {
    if (error.response) {
      const body = error.response.data
      const code = body?.error?.code ?? `HTTP_${error.response.status}`
      const message = body?.error?.message ?? `请求失败（HTTP ${error.response.status}）`
      return Promise.reject(new ApiError(code, message, error.response.status, body?.error?.details ?? []))
    }
    return Promise.reject(
      new ApiError('NETWORK_ERROR', '无法连接后端服务，请确认 Spring Boot 已在 8080 端口启动', null),
    )
  },
)

/** 把任意异常收敛为可展示的文案，避免把 undefined 抛到界面上。 */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.details.length > 0
      ? `${error.message}（${error.details.map((d) => `${d.field}: ${d.message}`).join('；')}）`
      : error.message
  }
  if (error instanceof Error) {
    return error.message
  }
  return String(error)
}
