// 密码重置页（/reset?token=xxx）
import React, { FormEvent, useState } from 'react';
import { authApi } from '../services/api';

export function ResetView({ onDone }: { onDone: () => void }) {
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [err, setErr] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (pw !== pw2) { setErr('两次输入的密码不一致'); return; }
    setSubmitting(true);
    try {
      const token = new URLSearchParams(window.location.search).get('token') || '';
      await authApi.resetPassword(token, pw);
      onDone();
    } catch (ex) {
      setErr(ex instanceof Error ? ex.message : '重置失败，请重试');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="post-detail-view">
      <div className="post-detail-card reset-card">
        <p className="eyebrow">TBFORUM / RESET</p>
        <h1>设置新密码</h1>
        {err && <div className="reset-err">{err}</div>}
        <form className="comment-form" onSubmit={submit}>
          <input type="password" value={pw} onChange={e => setPw(e.target.value)} minLength={8} maxLength={100} placeholder="新密码（至少 8 位）" required />
          <input type="password" value={pw2} onChange={e => setPw2(e.target.value)} minLength={8} maxLength={100} placeholder="再次输入新密码" required />
          <button className="primary" type="submit" disabled={submitting}>{submitting ? '提交中…' : '重置密码'}</button>
        </form>
      </div>
    </section>
  );
}
