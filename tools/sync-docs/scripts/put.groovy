import java.security.MessageDigest

class Put {
    static classLoader = Thread.currentThread().contextClassLoader
    static {
        def dir = new File(Put.class.protectionDomain.codesource.location.path).absoluteFile.parentFile.parentFile
        new File(dir, 'lib').listFiles().each { file ->
            if (!file.name.startsWith('.')) {
                classLoader.addURL(new URL('file:' + file.absolutePath))
            }
        }
    }
    static String CREATED = "A "
    static String UPDATED = "U "

    def server
    def dir
    def username
    def password
    def extension = '.gdoc'

    static main(args) {
        def put = new Put(args)
        put.run()
    }

    Put(args) {
        parseArgs(args)
        for (required in ['server', 'dir', 'username', 'password']) {
            assert this.getProperty(required), "Please specify a ${required} with -${required}=xxx"
        }

        if (!server.startsWith('http://')) {
            server = 'http://' + server
        }
        dir = new File(dir)
    }

    def run() {
        if (!dir.exists()) {
            println "Not found: \"${dir.absolutePath}\""
            System.exit(1)
        }
        doPut()
    }

    def parseArgs(args) {
        for (arg in args) {
            def matcher = arg =~ /-(.+)=(.+)/
            if (matcher.matches()) {
                this.setProperty(matcher[0][1], matcher[0][2])
            }
        }
    }

    def doPut() {
        def client = classLoader.loadClass('org.apache.http.impl.client.DefaultHttpClient').newInstance()
        login(client)
        for (file in dir.listFiles()) {
            if (!file.name.startsWith('.') && file.name.endsWith(extension)) {
                putPage(client, file.name.substring(0, file.name.length() - extension.length()), file.text)
            }
        }
    }

    def putPage(client, pageName, text) {
        // does the page exist?
        def exists = pageExists(client, pageName)

        def version, hash
        if (exists) {
            def resp = getPageResponse(client, pageName)
            version = findMessageWithin(resp, '<input type="hidden" name="version" value="', '" />')
            def pageText = getPageText(resp, pageName)
            hash = getHash(pageText)
        }

        // is it different?
        def doUpload = true
        if (exists) {
            def newHash = getHash(text)
            doUpload = hash != newHash
//            println "${hash}\n${newHash}\n$doUpload"
        }
        if (!doUpload) {
            return
        }
//        println "Uploading \"${pageName}\" (at ${server + "/save/" + URLEncoder.encode(pageName)})"
        def post = classLoader.loadClass('org.apache.http.client.methods.HttpPost').newInstance(server + "/save/" + URLEncoder.encode(pageName))
        post.setEntity(classLoader.loadClass('org.apache.http.entity.StringEntity').newInstance("version=${version}&title=${URLEncoder.encode(pageName)}&body=${URLEncoder.encode(text)}" as String))
        post.setHeader('Content-Type', 'application/x-www-form-urlencoded')
        def resp = responseToText(client.execute(post))
        //        println "resp ${resp}"
        def error = findError(resp)
        if (error) {
            println "Server error: \"${error}\""
            System.exit(1)
        }
        def message = findMessage(resp)
        println "${exists ? UPDATED : CREATED}${pageName}" + (message ? " (server: \"${message}\")" : '')
    }

    def pageExists(client, pageName) {
        def get = classLoader.loadClass('org.apache.http.client.methods.HttpGet').newInstance(server + "/" + URLEncoder.encode(pageName))
        def resp = responseToText(client.execute(get))
        //println resp
        !resp.contains("NOT_FOUND") && !resp.contains("HTTP Status 404")
    }

    def getPageResponse(client, pageName) {
        def post = classLoader.loadClass('org.apache.http.client.methods.HttpPost').newInstance(server + "/edit/" + URLEncoder.encode(pageName))
        responseToText(client.execute(post))
    }

    def findError(resp) {
        findMessageWithin(resp, '<div id="errors" class="errors">', '</div')
    }

    def findMessage(resp) {
        findMessageWithin(resp, '<div id="message" class="message">', '</div')
    }

    def findMessageWithin(String resp, String before, String after) {
        int start = resp.indexOf(before)
        if (start == -1) return null
        int end = resp.indexOf(after, start)
        resp.substring(start + before.length(), end).trim()
    }

    def login(client) {
        def post = classLoader.loadClass('org.apache.http.client.methods.HttpPost').newInstance(server + "/login?login=${username}&password=${password}")
        def resp = responseToText(client.execute(post))
//        response.entity.writeTo(System.out)
//        response.entity.consumeContent()
        if (resp.indexOf('<form action="/login"') != -1) {
            println "Invalid username/password?"
            System.exit(1)
        }
        return client
    }

    def responseToText(response) {
        def os = new ByteArrayOutputStream()
        response.entity.writeTo(os)
        os.toString()
    }

    def getPageText(String resp, String pageName) {
        def start = resp.indexOf('<textarea ')
        if (start == -1) {
            println "No wiki content found on page ${pageName}"
            return null
        }
        start = resp.indexOf('>', start) + 1
        def end = resp.indexOf('</textarea>')
        def text = resp.substring(start, end)
        text.replaceAll('&quot;', '"')
    }

    def getHash(String text) {
        MessageDigest md = MessageDigest.getInstance("SHA");
        md.update(text.getBytes());
        byte[] digest = md.digest();
        return new String(digest);
    }
}
