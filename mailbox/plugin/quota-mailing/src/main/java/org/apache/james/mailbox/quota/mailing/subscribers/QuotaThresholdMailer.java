/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.mailbox.quota.mailing.subscribers;

import java.util.Optional;

import javax.mail.MessagingException;

import org.apache.james.core.MailAddress;
import org.apache.james.core.User;
import org.apache.james.core.builder.MimeMessageBuilder;
import org.apache.james.eventsourcing.Event;
import org.apache.james.eventsourcing.Subscriber;
import org.apache.james.mailbox.quota.mailing.events.QuotaThresholdChangedEvent;
import org.apache.james.user.api.UsersRepository;
import org.apache.james.user.api.UsersRepositoryException;
import org.apache.mailet.MailetContext;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableList;

public class QuotaThresholdMailer implements Subscriber {
    private final MailetContext mailetContext;
    private final UsersRepository usersRepository;

    public QuotaThresholdMailer(MailetContext mailetContext, UsersRepository usersRepository) {
        this.mailetContext = mailetContext;
        this.usersRepository = usersRepository;
    }

    @Override
    public void handle(Event event) {
        if (event instanceof QuotaThresholdChangedEvent) {
            handleEvent((QuotaThresholdChangedEvent) event);
        }
    }

    private void handleEvent(QuotaThresholdChangedEvent event) {
        Optional<QuotaThresholdNotice> maybeNotice = QuotaThresholdNotice.builder()
            .countQuota(event.getCountQuota())
            .sizeQuota(event.getSizeQuota())
            .countThreshold(event.getCountHistoryEvolution())
            .sizeThreshold(event.getSizeHistoryEvolution())
            .build();

        maybeNotice.ifPresent(Throwing.consumer(notice -> sendNotice(notice, event.getAggregateId().getUser())));
    }

    private void sendNotice(QuotaThresholdNotice notice, User user) throws UsersRepositoryException, MessagingException {
        MailAddress sender = mailetContext.getPostmaster();
        MailAddress recipient = usersRepository.getMailAddressFor(user);

        mailetContext.sendMail(sender, ImmutableList.of(recipient),
            MimeMessageBuilder.mimeMessageBuilder()
                .addFrom(sender.asString())
                .addToRecipient(recipient.asString())
                .setSubject("Warning: Your email usage just exceeded a configured threshold")
                .setText(notice.generateReport())
                .build());
    }

}
