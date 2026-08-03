// 时间格式化工具
export const time = (v: string): string =>
  new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(v));
