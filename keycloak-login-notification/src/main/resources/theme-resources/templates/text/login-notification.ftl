<#ftl output_format="plainText">
${msg("loginNotificationIntro", username, realmName)}
<#if afterInactivity>${msg(inactiveInDays?then("loginNotificationReasonInactivity", "loginNotificationReasonInactivityHours"), inactiveInDays?then(inactiveDays, inactiveHours))}
</#if><#if fromNewIp>${msg("loginNotificationReasonNewIp")}
</#if>
${msg("loginNotificationDetails", loginTime, ipAddress, application, userAgent)}

${msg("loginNotificationAction", accountUrl)}
