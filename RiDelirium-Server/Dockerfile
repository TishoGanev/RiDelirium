FROM php:8.3-apache-bookworm

RUN docker-php-ext-install pdo_mysql

RUN a2enmod rewrite headers remoteip \
    && sed -ri 's!AllowOverride None!AllowOverride All!' /etc/apache2/apache2.conf

RUN printf 'ServerName ridersapi\nServerTokens Prod\nServerSignature Off\nRemoteIPHeader X-Forwarded-For\nRemoteIPInternalProxy 172.16.0.0/12 10.0.0.0/8 192.168.0.0/16\n' > /etc/apache2/conf-available/ridersapi.conf \
    && a2enconf ridersapi

RUN cp "$PHP_INI_DIR/php.ini-production" "$PHP_INI_DIR/php.ini"

COPY api/ /var/www/html/

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD curl -fs -o /dev/null -w '%{http_code}' http://localhost/v1/health | grep -qE '^(200|401|404)$' || exit 1
