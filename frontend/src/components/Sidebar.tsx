// 侧边栏：创建版块、全站动态、版块列表、热门标签
import type { Forum, TagView } from '../types';

type Props = {
  forums: Forum[];
  popularTags: TagView[];
  active: Forum | null;
  globalMode: boolean;
  activeTag: string | null;
  token: string;
  onEnterGlobal: () => void;
  onEnterForum: (f: Forum) => void;
  onEnterTag: (tag: string) => void;
  onExitTag: () => void;
  onCreateForum: () => void;
  onRequireLogin: () => void;
};

export function Sidebar({ forums, popularTags, active, globalMode, activeTag, token, onEnterGlobal, onEnterForum, onEnterTag, onExitTag, onCreateForum, onRequireLogin }: Props) {
  return (
    <aside>
      <button className="create-forum" onClick={() => token ? onCreateForum() : onRequireLogin()}>＋ 创建版块</button>
      <p className="label">发现</p>
      <nav>
        <button className={globalMode ? 'active' : ''} onClick={onEnterGlobal}><i>◉</i><span>全站动态</span></button>
      </nav>
      <p className="label">探索版块</p>
      <nav>
        {forums.map(f => (
          <button key={f.id} className={active?.id === f.id ? 'active' : ''} onClick={() => onEnterForum(f)}><i>◌</i><span>{f.name}</span></button>
        ))}
      </nav>
      {popularTags.length > 0 && (
        <>
          <p className="label">热门标签</p>
          <div className="tag-cloud">
            {popularTags.slice(0, 15).map(t => (
              <button key={t.id} className={'tag-pill' + (activeTag === t.name ? ' selected' : '')} onClick={() => onEnterTag(t.name)}>#{t.name}<span className="tag-count">{t.postCount}</span></button>
            ))}
          </div>
        </>
      )}
      {activeTag && <button className="plain" style={{ marginTop: '12px' }} onClick={onExitTag}>← 返回版块</button>}
      <div className="aside-note">
        <span>✦</span>
        <p><b>随缘而遇</b><br />没有推荐算法，只有偶然看见的好内容。</p>
      </div>
    </aside>
  );
}
