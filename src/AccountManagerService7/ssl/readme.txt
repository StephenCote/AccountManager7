https://gist.github.com/cecilemuller/9492b848eb8fe46d462abeb26656c4f8

    <Connector port="8443" protocol="org.apache.coyote.http11.Http11NioProtocol"
               maxThreads="150" SSLEnabled="true"
               maxParameterCount="1000"
               >
	 <UpgradeProtocol className="org.apache.coyote.http2.Http2Protocol" />
        <SSLHostConfig>
            <Certificate
                certificateFile="./conf/server.cert"
                certificateKeyFile="./conf/server.key"
            />

        </SSLHostConfig>
    </Connector>