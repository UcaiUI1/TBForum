// 版主面板状态：聚合 open/modList/reason1/reason2
import { useReducer } from 'react';
import type { UserView } from '../types';

type State = {
  open: boolean;
  modList: UserView[];
  reason1: string;
  reason2: string;
};

type Action =
  | { kind: 'open'; reason1: string; reason2: string }
  | { kind: 'close' }
  | { kind: 'setModList'; list: UserView[] }
  | { kind: 'removeMod'; username: string }
  | { kind: 'setReason1'; value: string }
  | { kind: 'setReason2'; value: string };

const initial: State = { open: false, modList: [], reason1: '', reason2: '' };

function reducer(state: State, action: Action): State {
  switch (action.kind) {
    case 'open': return { ...state, open: true, reason1: action.reason1, reason2: action.reason2 };
    case 'close': return { ...state, open: false };
    case 'setModList': return { ...state, modList: action.list };
    case 'removeMod': return { ...state, modList: state.modList.filter(m => m.username !== action.username) };
    case 'setReason1': return { ...state, reason1: action.value };
    case 'setReason2': return { ...state, reason2: action.value };
  }
}

export function useModState() {
  const [state, dispatch] = useReducer(reducer, initial);
  return {
    state,
    open: (reason1: string, reason2: string) => dispatch({ kind: 'open', reason1, reason2 }),
    close: () => dispatch({ kind: 'close' }),
    setModList: (list: UserView[]) => dispatch({ kind: 'setModList', list }),
    removeMod: (username: string) => dispatch({ kind: 'removeMod', username }),
    setReason1: (value: string) => dispatch({ kind: 'setReason1', value }),
    setReason2: (value: string) => dispatch({ kind: 'setReason2', value }),
  };
}
