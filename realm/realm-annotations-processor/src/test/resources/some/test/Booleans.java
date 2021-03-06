/*
 * Copyright 2014 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package some.test;

import io.realm.RealmObject;

public class Booleans extends RealmObject {
    private boolean done;
    private boolean isReady;
    private boolean mCompleted;
    private boolean anotherBoolean;

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public boolean isReady() {
        return isReady;
    }

    public void setReady(boolean isReady) {
        this.isReady = isReady;
    }

    public boolean ismCompleted() {
        return mCompleted;
    }

    public void setmCompleted(boolean mCompleted) {
        this.mCompleted = mCompleted;
    }

    public boolean getAnotherBoolean() {
        return anotherBoolean;
    }

    public void setAnotherBoolean(boolean anotherBoolean) {
        this.anotherBoolean = anotherBoolean;
    }
}
