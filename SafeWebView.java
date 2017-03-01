package com.yrz.atourong.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.webkit.JsPromptResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * @author
 * @email
 * @description  @link http://blog.csdn.net/leehong2005/article/details/11808557/
 */

public class SafeWebView extends WebView {
    private static final boolean DEBUG = true;
    private static final String VAR_ARG_PREFIX = "arg";
    private static final String MSG_PROMPT_HEADER = "MyApp:";
    /**
     * 对象名
     */
    private static final String KEY_INTERFACE_NAME = "obj";
    /**
     * 函数名
     */
    private static final String KEY_FUNCTION_NAME = "func";

    /**
     * 每个接口对应的方法中对应的参数类型
     */
    private Map<String, Map<String,List<Class>>> interfaceMethodTypeMap = new HashMap<>();

    /**
     * 参数数据类型
     */
    private static final String KEY_ARG_ARRAY = "args";
    /**
     * 要过滤的方法数组
     */
    private static final String[] mFilterMethods = {
            "getClass",
            "hashCode",
            "notify",
            "notifyAll",
            "equals",
            "toString",
            "wait",
    };

    /**
     * 缓存addJavascriptInterface的注册对象
     */
    private HashMap<String, Object> mJsInterfaceMap = new HashMap<>();

    /**
     * 缓存注入到JavaScript Context的js脚本
     */
    private String mJsStringCache = null;

    public SafeWebView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    public SafeWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SafeWebView(Context context) {
        super(context);
        init();
    }

    /**
     * WebView 初始化，设置监听，删除部分Android默认注册的JS接口
     */
    private void init() {
        setWebChromeClient(new WebChromeClientEx());
        setWebViewClient(new WebViewClientEx());
        safeSetting();

        removeUnSafeJavascriptImpl();
    }

    /**
     * 安全性设置
     */
    private void safeSetting() {
        getSettings().setSavePassword(false);
        getSettings().setAllowFileAccess(false);//设置为 false 将不能加载本地 html 文件
        if (Build.VERSION.SDK_INT >= 16) {
            getSettings().setAllowFileAccessFromFileURLs(false);
            getSettings().setAllowUniversalAccessFromFileURLs(false);
        }
    }

    /**
     * 检查SDK版本是否 >= 3.0 (API 11)
     */
    private boolean hasHoneycomb() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB;
    }

    /**
     * 检查SDK版本是否 >= 4.2 (API 17)
     */
    private boolean hasJellyBeanMR1() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1;
    }

    /**
     * 3.0 ~ 4.2 之间的版本需要移除 Google 注入的几个对象
     */
    @SuppressLint("NewApi")
    private boolean removeUnSafeJavascriptImpl() {
        if (hasHoneycomb() && !hasJellyBeanMR1()) {
//            removeJavascriptInterface("searchBoxJavaBridge_");
//            removeJavascriptInterface("accessibility");
//            removeJavascriptInterface("searchBoxJavaBridge_");
            super.removeJavascriptInterface("accessibilityTraversal");
            super.removeJavascriptInterface("accessibility");
            super.removeJavascriptInterface("accessibilityTraversal");
            return true;
        }
        return false;
    }

    @Override
    public void setWebViewClient(WebViewClient client) {
        if (hasJellyBeanMR1()) {
            super.setWebViewClient(client);
        } else {
            if (client instanceof WebViewClientEx) {
                super.setWebViewClient(client);
            } else if (client == null) {
                super.setWebViewClient(client);
            } else {
                throw new IllegalArgumentException(
                        "the \'client\' must be a subclass of the \'WebViewClientEx\'");
            }
        }
    }

    @Override
    public void setWebChromeClient(WebChromeClient client) {
        if (hasJellyBeanMR1()) {
            super.setWebChromeClient(client);
        } else {
            if (client instanceof WebChromeClientEx) {
                super.setWebChromeClient(client);
            } else if (client == null) {
                super.setWebChromeClient(client);
            } else {
                throw new IllegalArgumentException(
                        "the \'client\' must be a subclass of the \'WebChromeClientEx\'");
            }
        }
    }

    /**
     * 如果版本大于 4.2，漏洞已经被解决，直接调用基类的 addJavascriptInterface
     * 如果版本小于 4.2，则使用map缓存待注入对象
     */
    @SuppressLint("JavascriptInterface")
    @Override
    public void addJavascriptInterface(Object obj, String interfaceName) {
        if (TextUtils.isEmpty(interfaceName)) {
            return;
        }

        // 如果在4.2以上，直接调用基类的方法来注册
        if (hasJellyBeanMR1()) {
            super.addJavascriptInterface(obj, interfaceName);
        } else {
            mJsInterfaceMap.put(interfaceName, obj);
        }
    }

    /**
     * 删除待注入对象，
     * 如果版本为 4.2 以及 4.2 以上，则使用父类的removeJavascriptInterface。
     * 如果版本小于 4.2，则从缓存 map 中删除注入对象
     */
    @SuppressLint("NewApi")
    @Override
    public void removeJavascriptInterface(String interfaceName) {
        if (hasJellyBeanMR1()) {
            super.removeJavascriptInterface(interfaceName);
        } else {
            //清除缓存中的对应接口
            interfaceMethodTypeMap.remove(interfaceName);
            mJsInterfaceMap.remove(interfaceName);
            //每次 remove 之后，都需要重新构造 JS 注入
            mJsStringCache = null;
            injectJavascriptInterfaces();
        }
    }

    /**
     * 如果 WebView 是 SafeWebView 类型，则向 JavaScript Context 注入对象，确保 WebView 是有安全机制的
     */
    private void injectJavascriptInterfaces(WebView webView) {
        if (webView instanceof SafeWebView) {
            injectJavascriptInterfaces();
        }
    }

    /**
     * 注入我们构造的 JS
     */
    private void injectJavascriptInterfaces() {
        if (!TextUtils.isEmpty(mJsStringCache)) {
            loadUrl(mJsStringCache);
            return;
        }

        mJsStringCache = genJavascriptInterfacesString();
        loadUrl(mJsStringCache);
    }

    /**
     * 根据缓存的待注入java对象，生成映射的JavaScript代码，也就是桥梁(SDK4.2之前通过反射生成)
     */
    private String genJavascriptInterfacesString() {
        if (mJsInterfaceMap.size() == 0) {
            return null;
        }

        /*
         * 要注入的JS的格式，其中XXX为注入的对象的方法名，例如注入的对象中有一个方法A，那么这个XXX就是A
         * 如果这个对象中有多个方法，则会注册多个window.XXX_js_interface_name块，我们是用反射的方法遍历
         * 注入对象中的带有@JavaScripterInterface标注的方法
         *
         * javascript:(function JsAddJavascriptInterface_(){
         *   if(typeof(window.XXX_js_interface_name)!='undefined'){
         *       console.log('window.XXX_js_interface_name is exist!!');
         *   }else{
         *       window.XXX_js_interface_name={
         *           XXX:function(arg0,arg1){
         *               return prompt('MyApp:'+JSON.stringify({obj:'XXX_js_interface_name',func:'XXX_',args:[arg0,arg1]}));
         *           },
         *       };
         *   }
         * })()
         */

        Iterator<Map.Entry<String, Object>> iterator = mJsInterfaceMap.entrySet().iterator();
        //HEAD
        StringBuilder script = new StringBuilder();
        script.append("javascript:(function JsAddJavascriptInterface_(){");

        // 遍历待注入java对象，生成相应的js对象
        try {
            while (iterator.hasNext()) {
                Map.Entry<String, Object> entry = iterator.next();
                String interfaceName = entry.getKey();
                Object obj = entry.getValue();
                //每个接口都用一个Map对象存储
                interfaceMethodTypeMap.put(interfaceName, new HashMap<String, List<Class>>());
                // 生成相应的js方法
                createJsMethod(interfaceName, obj, script);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // End
        script.append("})()");
        return script.toString();
    }

    /**
     * 根据待注入的java对象，生成js方法
     *
     * @param interfaceName 对象名
     * @param obj           待注入的java对象
     * @param script        js代码
     */
    private void createJsMethod(String interfaceName, Object obj, StringBuilder script) {
        if (TextUtils.isEmpty(interfaceName) || (null == obj) || (null == script)) {
            return;
        }

        Class<? extends Object> objClass = obj.getClass();

        script.append("if(typeof(window.").append(interfaceName).append(")!='undefined'){");
        if (DEBUG) {
            script.append("    console.log('window." + interfaceName + "_js_interface_name is exist!!');");
        }

        script.append("}else {");
        script.append("    window.").append(interfaceName).append("={");

        // 通过反射机制，添加java对象的方法
        Method[] methods = objClass.getMethods();
        for (Method method : methods) {
            String methodName = method.getName();
            // 过滤掉Object类的方法，包括getClass()方法，因为在Js中就是通过getClass()方法来得到Runtime实例
            if (filterMethods(methodName)) {
                continue;
            }

            script.append("        ").append(methodName).append(":function(");
            // 添加方法的参数
            int argCount = method.getParameterTypes().length;
            //集合用于存储对应的方法的参数类型
            List<Class> argsType = new ArrayList<>();
            for (Class<?> argsTypeClaz : method.getParameterTypes()) {
                argsType.add(argsTypeClaz);
            }

            //保存对应接口方法参数的类型
            interfaceMethodTypeMap.get(interfaceName).put(methodName, argsType);


            if (argCount > 0) {
                int maxCount = argCount - 1;
                for (int i = 0; i < maxCount; ++i) {
                    script.append(VAR_ARG_PREFIX).append(i).append(",");
                }
                script.append(VAR_ARG_PREFIX).append(argCount - 1);
            }

            script.append(") {");

            // Add implementation
            if (method.getReturnType() != void.class) {
                script.append("            return ").append("prompt('").append(MSG_PROMPT_HEADER).append("'+");
            } else {
                script.append("            prompt('").append(MSG_PROMPT_HEADER).append("'+");
            }

            // Begin JSON
            script.append("JSON.stringify({");
            script.append(KEY_INTERFACE_NAME).append(":'").append(interfaceName).append("',");//接口名
            script.append(KEY_FUNCTION_NAME).append(":'").append(methodName).append("',");  // 方法名
            script.append(KEY_ARG_ARRAY).append(":[");  //方法参数
            //  添加参数到JSON串中
            if (argCount > 0) {
                int max = argCount - 1;
                for (int i = 0; i < max; i++) {
                    script.append(VAR_ARG_PREFIX).append(i).append(",");
                }
                script.append(VAR_ARG_PREFIX).append(max);
            }

            // End JSON
            script.append("]})");
            // End prompt
            script.append(");");
            // End function
            script.append("        }, ");
        }

        // End of obj
        script.append("    };");
        // End of if or else
        script.append("}");
    }

    /**
     * 检查是否是被过滤的方法
     */
    private boolean filterMethods(String methodName) {
        for (String method : mFilterMethods) {
            if (method.equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 利用反射，调用java对象的方法。
     * <p>
     * 从缓存中取出key=interfaceName的java对象，并调用其methodName方法
     *
     * @param result
     * @param interfaceName 对象名
     * @param methodName    方法名
     * @param args          参数列表
     * @return
     */
    private boolean invokeJSInterfaceMethod(JsPromptResult result, String interfaceName, String methodName, Object[] args) {

        boolean succeed = false;
        final Object obj = mJsInterfaceMap.get(interfaceName);
        if (null == obj) {
            result.cancel();
            return false;
        }

        Class<?>[] parameterTypes = null;
        int count = 0;
        if (args != null) {
            count = args.length;
        }

        if (count > 0) {
            /*parameterTypes = new Class[count];
            for (int i = 0; i < count; ++i) {
                parameterTypes[i] = getClassFromJsonObject(args[i]);
            }*/
            parameterTypes = getClassFromJsonCache(interfaceName, methodName);
        }

        try {
            Method method = obj.getClass().getMethod(methodName, parameterTypes);
            Object returnObj = method.invoke(obj, args); // 执行接口调用
            boolean isVoid = returnObj == null || returnObj.getClass() == void.class;
            String returnValue = isVoid ? "" : returnObj.toString();
            result.confirm(returnValue); // 通过prompt返回调用结果
            succeed = true;
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }

        result.cancel();
        return succeed;
    }

    /**
     * 解析出参数类型
     *
     * @param obj
     * @return
     */
    private Class<?> getClassFromJsonObject(Object obj) {
        if (obj == null) {
            //若参数为空，暂默认处理为String类型  TODO:
            return String.class;
        }
        Class<?> cls = obj.getClass();

        // js对象只支持int boolean string三种类型
        if (cls == Integer.class) {
            cls = Integer.TYPE;
        } else if (cls == Boolean.class) {
            cls = Boolean.TYPE;
        } else {
            cls = String.class;
        }

        return cls;
    }

    /**
     * 从缓存中取出对应方法的参数类型
     *
     * @param interfaceName
     * @param methodName
     * @return
     */
    private Class<?>[] getClassFromJsonCache(String interfaceName, String methodName) {
       Class<?>[] argsTypeArray = null;
        if(interfaceName != null && methodName != null){
            List<Class> classes = interfaceMethodTypeMap.get(interfaceName).get(methodName);
            argsTypeArray = classes.toArray(new Class<?>[classes.size()]);
        }
        return argsTypeArray;
    }

    /**
     * 解析JavaScript调用prompt的参数message，提取出对象名、方法名，以及参数列表，再利用反射，调用java对象的方法。
     *
     * @param view
     * @param url
     * @param message      MyApp:{"obj":"jsInterface","func":"onButtonClick","args":["从JS中传递过来的文本！！！"]}
     * @param defaultValue
     * @param result
     * @return
     */
    private boolean handleJsInterface(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
        String prefix = MSG_PROMPT_HEADER;
        if (!message.startsWith(prefix)) {
            return false;
        }

        String jsonStr = message.substring(prefix.length());
        try {
            JSONObject jsonObj = new JSONObject(jsonStr);
            // 对象名称
            String interfaceName = jsonObj.getString(KEY_INTERFACE_NAME);
            // 方法名称
            String methodName = jsonObj.getString(KEY_FUNCTION_NAME);
            // 参数数组
            JSONArray argsArray = jsonObj.getJSONArray(KEY_ARG_ARRAY);
            Object[] args = null;
            if (null != argsArray) {
                int count = argsArray.length();
                if (count > 0) {
                    args = new Object[count];
                    //根据缓存中的方法的参数类型来取值
                    List<Class> argsTypeList = interfaceMethodTypeMap.get(interfaceName).get(methodName);
                    for (int i = 0; i < count; ++i) {
                        if(argsTypeList.get(i).getSimpleName().equals(String.class.getSimpleName())){
                            args[i] = argsArray.getString(i);
                        }else if(argsTypeList.get(i).getSimpleName().equals(Boolean.class.getSimpleName())){
                            args[i] = argsArray.getBoolean(i);
                        }else if(argsTypeList.get(i).getSimpleName().equals(Integer.class.getSimpleName())){
                            args[i] = argsArray.getInt(i);
                        }

                        //这是之前的处理方式，会因为传递的参数类型与实际的参数类型不匹配，导致反射调用时方法不匹配
                       /* Object arg = argsArray.get(i);
                        if (!arg.toString().equals("null")) {
                            args[i] = arg;
                        } else {
                            args[i] = null;
                        }*/
                    }
                }
            }

            if (invokeJSInterfaceMethod(result, interfaceName, methodName, args)) {
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        result.cancel();
        return false;
    }

    public class WebChromeClientEx extends WebChromeClient {
        @Override
        public final void onProgressChanged(WebView view, int newProgress) {
            injectJavascriptInterfaces(view);
            super.onProgressChanged(view, newProgress);
        }

        @Override
        public final boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            if (view instanceof SafeWebView) {
                if (handleJsInterface(view, url, message, defaultValue, result)) {
                    return true;
                }
            }

            return super.onJsPrompt(view, url, message, defaultValue, result);
        }

        @Override
        public final void onReceivedTitle(WebView view, String title) {
            injectJavascriptInterfaces(view);
        }
    }

    public class WebViewClientEx extends WebViewClient {
        @Override
        public void onLoadResource(WebView view, String url) {
            injectJavascriptInterfaces(view);
            super.onLoadResource(view, url);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            injectJavascriptInterfaces(view);
            super.doUpdateVisitedHistory(view, url, isReload);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            injectJavascriptInterfaces(view);
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectJavascriptInterfaces(view);
            super.onPageFinished(view, url);
        }
    }
}