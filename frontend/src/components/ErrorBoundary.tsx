// 错误边界：捕获子组件渲染异常，避免整个应用崩溃
import React from 'react';

export class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { error: string | null }> {
  state = { error: null as string | null };
  static getDerivedStateFromError(e: Error) { return { error: e.message }; }
  componentDidCatch(e: Error, i: React.ErrorInfo) { console.warn('UI:', e, i); }
  render() {
    return this.state.error ? (
      <div style={{ padding: 40, color: '#8ef5dc', textAlign: 'center', background: '#07101d', minHeight: '100vh' }}>
        <h2>页面出错了</h2>
        <p style={{ color: '#617895' }}>{this.state.error}</p>
        <button onClick={() => location.reload()} style={{ marginTop: 20, padding: '8px 20px', background: '#1a2c44', color: '#8ef5dc', border: '1px solid #33516a', borderRadius: 6, cursor: 'pointer' }}>刷新页面</button>
      </div>
    ) : this.props.children;
  }
}
