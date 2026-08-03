// 举报弹窗状态：用 useReducer 聚合 modal/reason/submitting
import { useReducer } from 'react';
import type { ReportReasons } from '../types';

type State = {
  modal: { type: 'POST' | 'COMMENT'; id: number; reasons: ReportReasons } | null;
  reason: string;
  submitting: boolean;
};

type Action =
  | { kind: 'open'; payload: { type: 'POST' | 'COMMENT'; id: number; reasons: ReportReasons } }
  | { kind: 'close' }
  | { kind: 'setReason'; value: string }
  | { kind: 'setSubmitting'; value: boolean };

const initial: State = { modal: null, reason: '', submitting: false };

function reducer(state: State, action: Action): State {
  switch (action.kind) {
    case 'open': return { modal: action.payload, reason: '', submitting: false };
    case 'close': return { ...state, modal: null, reason: '' };
    case 'setReason': return { ...state, reason: action.value };
    case 'setSubmitting': return { ...state, submitting: action.value };
  }
}

export function useReportState() {
  const [state, dispatch] = useReducer(reducer, initial);
  return {
    state,
    open: (payload: { type: 'POST' | 'COMMENT'; id: number; reasons: ReportReasons }) => dispatch({ kind: 'open', payload }),
    close: () => dispatch({ kind: 'close' }),
    setReason: (value: string) => dispatch({ kind: 'setReason', value }),
    setSubmitting: (value: boolean) => dispatch({ kind: 'setSubmitting', value }),
  };
}
