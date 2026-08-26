package cn.tbforum.controller;

import cn.tbforum.dto.Dtos.PostView;
import cn.tbforum.repo.ForumRepository;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.TagRepository;
import cn.tbforum.service.PostService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RssController {
    private static final DateTimeFormatter RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME;
    private final PostService postService;
    private final ForumRepository forums;
    private final TagRepository tags;
    private final PostRepository posts;

    public RssController(PostService p, ForumRepository f, TagRepository t, PostRepository pr) {
        postService = p;
        forums = f;
        tags = t;
        posts = pr;
    }

    private static String esc(String s) {
        return s == null
                ? ""
                : s.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;")
                        .replace("'", "&apos;");
    }

    @GetMapping(value = "/rss.xml", produces = "application/rss+xml; charset=utf-8")
    public String rss(HttpServletRequest req) {
        String base = req.getScheme() + "://" + req.getServerName()
                + ("80".equals(String.valueOf(req.getServerPort())) ? "" : ":" + req.getServerPort());
        var posts = postService.feed("latest", 0, 20, null).getContent();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<rss version=\"2.0\"><channel>");
        sb.append("<title>TBForum · 随缘而遇</title>");
        sb.append("<link>").append(base).append("/</link>");
        sb.append("<description>没有推荐算法，只有偶然遇见的好内容的社区。</description>");
        sb.append("<language>zh-CN</language>");
        for (PostView p : posts) {
            sb.append("<item>");
            sb.append("<title>").append(esc(p.title())).append("</title>");
            sb.append("<link>").append(base).append("/post/").append(p.id()).append("</link>");
            sb.append("<guid>").append(base).append("/post/").append(p.id()).append("</guid>");
            sb.append("<author>").append(esc(p.author())).append("</author>");
            sb.append("<pubDate>")
                    .append(RFC1123.format(p.createdAt().atZone(ZoneOffset.UTC)))
                    .append("</pubDate>");
            sb.append("<description>")
                    .append(esc(p.content().length() > 500 ? p.content().substring(0, 500) + "…" : p.content()))
                    .append("</description>");
            sb.append("</item>");
        }
        sb.append("</channel></rss>");
        return sb.toString();
    }

    @GetMapping(value = "/sitemap.xml", produces = "application/xml; charset=utf-8")
    public String sitemap(HttpServletRequest req) {
        String base = req.getScheme() + "://" + req.getServerName()
                + ("80".equals(String.valueOf(req.getServerPort())) ? "" : ":" + req.getServerPort());
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        sb.append("<url><loc>").append(base).append("/</loc></url>");
        sb.append("<url><loc>").append(base).append("/global</loc></url>");
        forums.findByDeletedFalse(PageRequest.of(0, 200))
                .forEach(f -> sb.append("<url><loc>")
                        .append(base)
                        .append("/forum/")
                        .append(f.slug)
                        .append("</loc></url>"));
        tags.findTop50ByOrderByPostCountDesc()
                .forEach(t -> sb.append("<url><loc>")
                        .append(base)
                        .append("/tag/")
                        .append(esc(t.name))
                        .append("</loc></url>"));
        posts.findAllByDeletedFalseOrderByCreatedAtDesc(PageRequest.of(0, 200))
                .forEach(p -> sb.append("<url><loc>")
                        .append(base)
                        .append("/post/")
                        .append(p.id)
                        .append("</loc></url>"));
        sb.append("</urlset>");
        return sb.toString();
    }

    @GetMapping(value = "/robots.txt", produces = "text/plain; charset=utf-8")
    public String robots(HttpServletRequest req) {
        String base = req.getScheme() + "://" + req.getServerName()
                + ("80".equals(String.valueOf(req.getServerPort())) ? "" : ":" + req.getServerPort());
        return "User-agent: *\nAllow: /\nSitemap: " + base + "/sitemap.xml\n";
    }
}
