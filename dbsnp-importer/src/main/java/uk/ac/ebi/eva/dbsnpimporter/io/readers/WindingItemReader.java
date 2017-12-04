/*
 * Copyright 2017 EMBL - European Bioinformatics Institute
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.ac.ebi.eva.dbsnpimporter.io.readers;

import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStreamReader;

import java.util.Collection;
import java.util.LinkedList;

/**
 * The winding reader takes a reader that returns an element in each read call and groups them together in a single
 * read call. This class can be used with any kind of item reader, beware that some subtypes of ItemReaders like
 * {@link ItemStreamReader} require special operations before and after read operations. If your reader extends
 * {@link ItemStreamReader} please use {@link WindingItemStreamReader}
 *
 * @param <T>
 */
public class WindingItemReader<T> implements ItemReader<Collection<T>> {

    private final ItemReader<T> reader;

    public WindingItemReader(ItemReader<T> reader) {
        this.reader = reader;
    }

    @Override
    public Collection<T> read() throws Exception {
        Collection<T> items = new LinkedList<>();
        T item;

        while ((item = reader.read()) != null) {
            items.add(item);
        }

        if (!items.isEmpty()) {
            return items;
        } else {
            return null;
        }
    }

    protected ItemReader<T> getReader() {
        return reader;
    }
}
