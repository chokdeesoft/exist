xquery version "1.0";

declare option exist:serialize "method=xhtml";

import module namespace testmod="http://exist-db.org/xquery/testmod"
at "xmldb:exist:///db/webtest/testmod.xq";

<html>
    <head>
        <title>XQueryServlet: import module from database</title>
    </head>
    <body>
        {testmod:hello-world()}
        
        <p id="base-uri">{base-uri()}</p>
    </body>
</html>