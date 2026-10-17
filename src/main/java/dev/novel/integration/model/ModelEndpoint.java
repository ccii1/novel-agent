package dev.novel.integration.model;

import java.net.URI;
import java.util.Set;

public final class ModelEndpoint {
    private ModelEndpoint() {}
    public static URI resolve(String endpoint) {
        URI uri=URI.create(endpoint);
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("模型地址需要有效主机，凭据通过请求头提供");
        boolean local=Set.of("127.0.0.1","localhost","::1","[::1]").contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
        if(!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && local))
            throw new IllegalArgumentException("远程模型地址必须使用 HTTPS，本机回环地址可以使用 HTTP");
        String path=uri.getPath().replaceAll("/+$","");
        if(path.endsWith("/chat/completions")) return uri;
        String query=uri.getRawQuery()==null?"":"?"+uri.getRawQuery();
        return URI.create(uri.getScheme()+"://"+uri.getRawAuthority()+path+"/chat/completions"+query);
    }
}
