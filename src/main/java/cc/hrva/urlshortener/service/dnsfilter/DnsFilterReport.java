package cc.hrva.urlshortener.service.dnsfilter;

import java.util.List;

public record DnsFilterReport(String host, boolean exists, List<DnsFilterAnswer> answers) {

    public List<DnsFilterProvider> blocking() {
        return answers.stream()
                .filter(answer -> answer.status() == DnsFilterStatus.BLOCKED)
                .map(DnsFilterAnswer::provider)
                .toList();
    }

    public boolean anyUnanswered() {
        return answers.stream().anyMatch(answer -> answer.status() == DnsFilterStatus.NO_ANSWER);
    }

}
